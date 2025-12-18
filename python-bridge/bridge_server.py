#!/usr/bin/env python3
"""
Python Bridge Server for Kotlin Reticulum Interop Testing.

This server receives JSON commands over stdin, executes them against
the Python RNS library, and returns JSON responses over stdout.

Protocol:
    Request:  {"id": "...", "command": "...", "params": {...}}
    Response: {"id": "...", "success": true, "result": {...}}
    Error:    {"id": "...", "success": false, "error": "..."}

All byte arrays are hex-encoded strings.
"""

import sys
import os
import json
import traceback

# Add RNS Cryptography to path directly (bypass RNS __init__.py)
rns_path = os.environ.get('PYTHON_RNS_PATH', '../../../Reticulum')
# Convert to absolute path
rns_path = os.path.abspath(rns_path)
sys.path.insert(0, os.path.join(rns_path, 'RNS', 'Cryptography'))
sys.path.insert(0, rns_path)

import hashlib

# Import cryptography modules directly from the Cryptography directory
# This bypasses RNS/__init__.py which would load all interfaces
import importlib.util

def load_module_from_path(name, path):
    """Load a module directly from file path."""
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module

# Load cryptography modules directly
crypto_path = os.path.join(rns_path, 'RNS', 'Cryptography')

# Load HMAC first (needed by others)
HMAC = load_module_from_path('RNS_HMAC', os.path.join(crypto_path, 'HMAC.py'))

# Load X25519
X25519 = load_module_from_path('RNS_X25519', os.path.join(crypto_path, 'X25519.py'))

# Load HKDF (depends on HMAC)
# We need to patch the import
hkdf_code = open(os.path.join(crypto_path, 'HKDF.py')).read()
hkdf_code = hkdf_code.replace('from RNS.Cryptography import HMAC', '')
hkdf_module = type(sys)('RNS_HKDF')
hkdf_module.HMAC = HMAC
exec(compile(hkdf_code, 'HKDF.py', 'exec'), hkdf_module.__dict__)
sys.modules['RNS_HKDF'] = hkdf_module
HKDF = hkdf_module

# Load PKCS7 (functions are in PKCS7.PKCS7 class)
PKCS7_module = load_module_from_path('RNS_PKCS7', os.path.join(crypto_path, 'PKCS7.py'))
PKCS7 = PKCS7_module.PKCS7

# Load internal pure Python AES implementation
aes128_module = load_module_from_path('RNS_AES128', os.path.join(crypto_path, 'aes', 'aes128.py'))
aes256_module = load_module_from_path('RNS_AES256', os.path.join(crypto_path, 'aes', 'aes256.py'))

class AES_128_CBC:
    @staticmethod
    def encrypt(plaintext, key, iv):
        if len(key) != 16:
            raise ValueError(f"Invalid key length {len(key)*8} for AES-128")
        cipher = aes128_module.AES128(key)
        return cipher.encrypt(plaintext, iv)

    @staticmethod
    def decrypt(ciphertext, key, iv):
        if len(key) != 16:
            raise ValueError(f"Invalid key length {len(key)*8} for AES-128")
        cipher = aes128_module.AES128(key)
        return cipher.decrypt(ciphertext, iv)

class AES_256_CBC:
    @staticmethod
    def encrypt(plaintext, key, iv):
        if len(key) != 32:
            raise ValueError(f"Invalid key length {len(key)*8} for AES-256")
        cipher = aes256_module.AES256(key)
        return cipher.encrypt_cbc(plaintext, iv)

    @staticmethod
    def decrypt(ciphertext, key, iv):
        if len(key) != 32:
            raise ValueError(f"Invalid key length {len(key)*8} for AES-256")
        cipher = aes256_module.AES256(key)
        return cipher.decrypt_cbc(ciphertext, iv)

# Load Token (depends on HMAC, PKCS7, AES)
token_code = open(os.path.join(crypto_path, 'Token.py')).read()
token_module = type(sys)('RNS_Token')
token_module.os = os
token_module.time = __import__('time')
token_module.HMAC = HMAC
token_module.PKCS7 = PKCS7
token_module.AES = type(sys)('AES')
token_module.AES.AES_128_CBC = AES_128_CBC
token_module.AES.AES_256_CBC = AES_256_CBC
token_module.AES_128_CBC = AES_128_CBC
token_module.AES_256_CBC = AES_256_CBC
# Remove import statements and execute
import re
token_code = re.sub(r'^from RNS\.Cryptography.*$', '', token_code, flags=re.MULTILINE)
token_code = re.sub(r'^import (?:os|time)$', '', token_code, flags=re.MULTILINE)
exec(compile(token_code, 'Token.py', 'exec'), token_module.__dict__)
sys.modules['RNS_Token'] = token_module
Token = token_module

# Pre-register a fake RNS.Cryptography.Hashes module to satisfy eddsa.py import
# This prevents triggering the full RNS import chain
fake_rns = type(sys)('RNS')
fake_crypto = type(sys)('RNS.Cryptography')
fake_hashes = type(sys)('RNS.Cryptography.Hashes')
fake_hashes.sha512 = lambda data: hashlib.sha512(data).digest()
sys.modules['RNS'] = fake_rns
sys.modules['RNS.Cryptography'] = fake_crypto
sys.modules['RNS.Cryptography.Hashes'] = fake_hashes
fake_rns.Cryptography = fake_crypto
fake_crypto.Hashes = fake_hashes



def hex_to_bytes(hex_str):
    """Convert hex string to bytes."""
    return bytes.fromhex(hex_str)


def bytes_to_hex(data):
    """Convert bytes to hex string."""
    return data.hex()


# Command handlers

def cmd_x25519_generate(params):
    """Generate X25519 keypair from seed."""
    seed = hex_to_bytes(params['seed'])
    priv = X25519.X25519PrivateKey.from_private_bytes(seed)
    pub = priv.public_key()
    return {
        'private_key': bytes_to_hex(priv.private_bytes()),
        'public_key': bytes_to_hex(pub.public_bytes())
    }


def cmd_x25519_public_from_private(params):
    """Derive public key from private key."""
    private_key = hex_to_bytes(params['private_key'])
    priv = X25519.X25519PrivateKey.from_private_bytes(private_key)
    pub = priv.public_key()
    return {
        'public_key': bytes_to_hex(pub.public_bytes())
    }


def cmd_x25519_exchange(params):
    """Perform X25519 key exchange."""
    private_key = hex_to_bytes(params['private_key'])
    peer_public_key = hex_to_bytes(params['peer_public_key'])

    priv = X25519.X25519PrivateKey.from_private_bytes(private_key)
    pub = X25519.X25519PublicKey.from_public_bytes(peer_public_key)
    shared = priv.exchange(pub)

    return {
        'shared_secret': bytes_to_hex(shared)
    }


def cmd_ed25519_generate(params):
    """Generate Ed25519 keypair from seed."""
    seed = hex_to_bytes(params['seed'])

    # Use the pure25519 implementation (from Cryptography path)
    from pure25519.ed25519_oop import SigningKey
    sk = SigningKey(seed)

    return {
        'private_key': bytes_to_hex(seed),
        'public_key': bytes_to_hex(sk.vk_s)
    }


def cmd_ed25519_sign(params):
    """Sign message with Ed25519."""
    private_key = hex_to_bytes(params['private_key'])
    message = hex_to_bytes(params['message'])

    from pure25519.ed25519_oop import SigningKey
    sk = SigningKey(private_key)
    signature = sk.sign(message)

    return {
        'signature': bytes_to_hex(signature)
    }


def cmd_ed25519_verify(params):
    """Verify Ed25519 signature."""
    public_key = hex_to_bytes(params['public_key'])
    message = hex_to_bytes(params['message'])
    signature = hex_to_bytes(params['signature'])

    from pure25519.ed25519_oop import VerifyingKey
    try:
        vk = VerifyingKey(public_key)
        vk.verify(signature, message)
        return {'valid': True}
    except Exception:
        return {'valid': False}


def cmd_sha256(params):
    """Compute SHA-256 hash."""
    data = hex_to_bytes(params['data'])
    hash_result = hashlib.sha256(data).digest()
    return {
        'hash': bytes_to_hex(hash_result)
    }


def cmd_sha512(params):
    """Compute SHA-512 hash."""
    data = hex_to_bytes(params['data'])
    hash_result = hashlib.sha512(data).digest()
    return {
        'hash': bytes_to_hex(hash_result)
    }


def cmd_hmac_sha256(params):
    """Compute HMAC-SHA256."""
    key = hex_to_bytes(params['key'])
    data = hex_to_bytes(params['message'])

    hmac_result = HMAC.new(key, data).digest()
    return {
        'hmac': bytes_to_hex(hmac_result)
    }


def cmd_hkdf(params):
    """Derive key using HKDF."""
    length = int(params['length'])
    ikm = hex_to_bytes(params['ikm'])
    salt = hex_to_bytes(params['salt']) if params.get('salt') else None
    info = hex_to_bytes(params['info']) if params.get('info') else None

    derived = HKDF.hkdf(length=length, derive_from=ikm, salt=salt, context=info)
    return {
        'derived_key': bytes_to_hex(derived)
    }


def cmd_pkcs7_pad(params):
    """Apply PKCS7 padding."""
    data = hex_to_bytes(params['data'])
    padded = PKCS7.pad(data)
    return {
        'padded': bytes_to_hex(padded)
    }


def cmd_pkcs7_unpad(params):
    """Remove PKCS7 padding."""
    data = hex_to_bytes(params['data'])
    unpadded = PKCS7.unpad(data)
    return {
        'unpadded': bytes_to_hex(unpadded)
    }


def cmd_aes_encrypt(params):
    """Encrypt with AES-CBC."""
    plaintext = hex_to_bytes(params['plaintext'])
    key = hex_to_bytes(params['key'])
    iv = hex_to_bytes(params['iv'])
    mode = params.get('mode', 'AES_256_CBC')

    # Pad plaintext
    padded = PKCS7.pad(plaintext)

    if mode == 'AES_128_CBC':
        ciphertext = AES_128_CBC.encrypt(padded, key, iv)
    else:
        ciphertext = AES_256_CBC.encrypt(padded, key, iv)

    return {
        'ciphertext': bytes_to_hex(ciphertext)
    }


def cmd_aes_decrypt(params):
    """Decrypt with AES-CBC."""
    ciphertext = hex_to_bytes(params['ciphertext'])
    key = hex_to_bytes(params['key'])
    iv = hex_to_bytes(params['iv'])
    mode = params.get('mode', 'AES_256_CBC')

    if mode == 'AES_128_CBC':
        plaintext = AES_128_CBC.decrypt(ciphertext, key, iv)
    else:
        plaintext = AES_256_CBC.decrypt(ciphertext, key, iv)

    # Unpad
    unpadded = PKCS7.unpad(plaintext)
    return {
        'plaintext': bytes_to_hex(unpadded)
    }


def cmd_token_encrypt(params):
    """Encrypt using Token (modified Fernet)."""
    key = hex_to_bytes(params['key'])
    plaintext = hex_to_bytes(params['plaintext'])
    iv = hex_to_bytes(params['iv']) if params.get('iv') else None

    token_obj = Token.Token(key)

    if iv:
        # For reproducibility, we need to manually construct the token
        # This matches the encrypt() method but with fixed IV
        mode = token_obj.mode
        ciphertext = mode.encrypt(
            plaintext=PKCS7.pad(plaintext),
            key=token_obj._encryption_key,
            iv=iv
        )
        signed_parts = iv + ciphertext
        hmac_val = HMAC.new(token_obj._signing_key, signed_parts).digest()
        token_bytes = signed_parts + hmac_val
    else:
        token_bytes = token_obj.encrypt(plaintext)

    return {
        'token': bytes_to_hex(token_bytes)
    }


def cmd_token_decrypt(params):
    """Decrypt using Token."""
    key = hex_to_bytes(params['key'])
    token_bytes = hex_to_bytes(params['token'])

    token_obj = Token.Token(key)
    plaintext = token_obj.decrypt(token_bytes)

    return {
        'plaintext': bytes_to_hex(plaintext)
    }


def cmd_token_verify_hmac(params):
    """Verify Token HMAC."""
    key = hex_to_bytes(params['key'])
    token_bytes = hex_to_bytes(params['token'])

    token_obj = Token.Token(key)
    valid = token_obj.verify_hmac(token_bytes)

    return {
        'valid': valid
    }


def cmd_identity_from_private_key(params):
    """Create identity from 64-byte private key (X25519 + Ed25519)."""
    private_key = hex_to_bytes(params['private_key'])

    # Split private key: X25519 (32) + Ed25519 (32)
    x25519_prv_bytes = private_key[:32]
    ed25519_prv_bytes = private_key[32:]

    # Derive public keys
    x25519_prv = X25519.X25519PrivateKey.from_private_bytes(x25519_prv_bytes)
    x25519_pub = x25519_prv.public_key()
    x25519_pub_bytes = x25519_pub.public_bytes()

    from pure25519.ed25519_oop import SigningKey
    ed25519_sk = SigningKey(ed25519_prv_bytes)
    ed25519_pub_bytes = ed25519_sk.vk_s

    # Full public key
    public_key = x25519_pub_bytes + ed25519_pub_bytes

    # Identity hash = truncated_hash(public_key)
    identity_hash = hashlib.sha256(public_key).digest()[:16]

    return {
        'public_key': bytes_to_hex(public_key),
        'hash': bytes_to_hex(identity_hash),
        'hexhash': identity_hash.hex()
    }


def cmd_identity_encrypt(params):
    """Encrypt plaintext for an identity using ephemeral ECDH."""
    public_key = hex_to_bytes(params['public_key'])
    plaintext = hex_to_bytes(params['plaintext'])
    ephemeral_private = hex_to_bytes(params['ephemeral_private']) if params.get('ephemeral_private') else None
    iv = hex_to_bytes(params['iv']) if params.get('iv') else None

    # Extract X25519 public key (first 32 bytes)
    x25519_pub_bytes = public_key[:32]
    x25519_pub = X25519.X25519PublicKey.from_public_bytes(x25519_pub_bytes)

    # Generate or use provided ephemeral key
    if ephemeral_private:
        ephemeral_prv = X25519.X25519PrivateKey.from_private_bytes(ephemeral_private)
    else:
        ephemeral_prv = X25519.X25519PrivateKey.generate()

    ephemeral_pub_bytes = ephemeral_prv.public_key().public_bytes()

    # ECDH exchange
    shared_key = ephemeral_prv.exchange(x25519_pub)

    # Identity hash for salt
    identity_hash = hashlib.sha256(public_key).digest()[:16]

    # HKDF key derivation
    derived_key = HKDF.hkdf(
        length=64,
        derive_from=shared_key,
        salt=identity_hash,
        context=None
    )

    # Token encryption
    token_obj = Token.Token(derived_key)

    if iv:
        # Use provided IV for reproducibility
        ciphertext = token_obj.mode.encrypt(
            plaintext=PKCS7.pad(plaintext),
            key=token_obj._encryption_key,
            iv=iv
        )
        signed_parts = iv + ciphertext
        hmac_val = HMAC.new(token_obj._signing_key, signed_parts).digest()
        token_bytes = signed_parts + hmac_val
    else:
        token_bytes = token_obj.encrypt(plaintext)

    # Return ephemeral_pub + token
    result = ephemeral_pub_bytes + token_bytes

    return {
        'ciphertext': bytes_to_hex(result),
        'ephemeral_public': bytes_to_hex(ephemeral_pub_bytes),
        'shared_key': bytes_to_hex(shared_key),
        'derived_key': bytes_to_hex(derived_key),
        'identity_hash': bytes_to_hex(identity_hash)
    }


def cmd_identity_decrypt(params):
    """Decrypt ciphertext encrypted for an identity."""
    private_key = hex_to_bytes(params['private_key'])
    ciphertext = hex_to_bytes(params['ciphertext'])

    # Split private key: X25519 (32) + Ed25519 (32)
    x25519_prv_bytes = private_key[:32]
    x25519_prv = X25519.X25519PrivateKey.from_private_bytes(x25519_prv_bytes)

    # Derive public key for identity hash
    x25519_pub_bytes = x25519_prv.public_key().public_bytes()

    # Get Ed25519 public key too for full identity hash
    ed25519_prv_bytes = private_key[32:]
    from pure25519.ed25519_oop import SigningKey
    ed25519_sk = SigningKey(ed25519_prv_bytes)
    ed25519_pub_bytes = ed25519_sk.vk_s

    full_public_key = x25519_pub_bytes + ed25519_pub_bytes
    identity_hash = hashlib.sha256(full_public_key).digest()[:16]

    # Extract ephemeral public key and token
    peer_pub_bytes = ciphertext[:32]
    token_bytes = ciphertext[32:]

    peer_pub = X25519.X25519PublicKey.from_public_bytes(peer_pub_bytes)

    # ECDH exchange
    shared_key = x25519_prv.exchange(peer_pub)

    # HKDF key derivation
    derived_key = HKDF.hkdf(
        length=64,
        derive_from=shared_key,
        salt=identity_hash,
        context=None
    )

    # Token decryption
    token_obj = Token.Token(derived_key)
    plaintext = token_obj.decrypt(token_bytes)

    return {
        'plaintext': bytes_to_hex(plaintext),
        'shared_key': bytes_to_hex(shared_key),
        'derived_key': bytes_to_hex(derived_key)
    }


def cmd_identity_sign(params):
    """Sign a message with an identity's Ed25519 key."""
    private_key = hex_to_bytes(params['private_key'])
    message = hex_to_bytes(params['message'])

    # Ed25519 private key is second 32 bytes
    ed25519_prv_bytes = private_key[32:]

    from pure25519.ed25519_oop import SigningKey
    sk = SigningKey(ed25519_prv_bytes)
    signature = sk.sign(message)

    return {
        'signature': bytes_to_hex(signature)
    }


def cmd_identity_verify(params):
    """Verify a signature with an identity's public key."""
    public_key = hex_to_bytes(params['public_key'])
    message = hex_to_bytes(params['message'])
    signature = hex_to_bytes(params['signature'])

    # Ed25519 public key is second 32 bytes
    ed25519_pub_bytes = public_key[32:]

    from pure25519.ed25519_oop import VerifyingKey
    try:
        vk = VerifyingKey(ed25519_pub_bytes)
        vk.verify(signature, message)
        return {'valid': True}
    except Exception:
        return {'valid': False}


def cmd_identity_hash(params):
    """Compute identity hash from public key."""
    public_key = hex_to_bytes(params['public_key'])

    # Identity hash is truncated_hash(public_key) = SHA256[0:16]
    full_hash = hashlib.sha256(public_key).digest()
    truncated = full_hash[:16]

    return {
        'hash': bytes_to_hex(truncated),
        'full_hash': bytes_to_hex(full_hash)
    }


def cmd_destination_hash(params):
    """Compute destination hash."""
    identity_hash = hex_to_bytes(params['identity_hash'])
    app_name = params['app_name']
    aspects_param = params.get('aspects', '')

    # Parse aspects - can be comma-separated string or already a list
    if isinstance(aspects_param, list):
        aspects = aspects_param
    elif aspects_param:
        aspects = aspects_param.split(',')
    else:
        aspects = []

    # Build full name
    name_parts = [app_name] + aspects
    full_name = ".".join(name_parts)

    # Name hash = SHA256(name.encode())[0:10]
    name_hash = hashlib.sha256(full_name.encode('utf-8')).digest()[:10]

    # Destination hash = SHA256(name_hash + identity_hash)[0:16]
    addr_hash_material = name_hash + identity_hash
    dest_hash = hashlib.sha256(addr_hash_material).digest()[:16]

    return {
        'name_hash': bytes_to_hex(name_hash),
        'destination_hash': bytes_to_hex(dest_hash),
        'full_name': full_name
    }


def cmd_truncated_hash(params):
    """Compute truncated hash (first 16 bytes of SHA256)."""
    data = hex_to_bytes(params['data'])
    full_hash = hashlib.sha256(data).digest()
    truncated = full_hash[:16]

    return {
        'hash': bytes_to_hex(truncated),
        'full_hash': bytes_to_hex(full_hash)
    }


def cmd_name_hash(params):
    """Compute name hash (first 10 bytes of SHA256)."""
    name = params['name']
    full_hash = hashlib.sha256(name.encode('utf-8')).digest()
    name_hash = full_hash[:10]

    return {
        'hash': bytes_to_hex(name_hash)
    }


def cmd_packet_flags(params):
    """Compute packet flags byte from components."""
    header_type = int(params['header_type'])
    context_flag = int(params['context_flag'])
    transport_type = int(params['transport_type'])
    destination_type = int(params['destination_type'])
    packet_type = int(params['packet_type'])

    flags = (header_type << 6) | (context_flag << 5) | (transport_type << 4) | (destination_type << 2) | packet_type

    return {
        'flags': flags,
        'flags_hex': f'{flags:02x}'
    }


def cmd_packet_parse_flags(params):
    """Parse packet flags byte into components."""
    flags = int(params['flags'])

    header_type = (flags & 0b01000000) >> 6
    context_flag = (flags & 0b00100000) >> 5
    transport_type = (flags & 0b00010000) >> 4
    destination_type = (flags & 0b00001100) >> 2
    packet_type = (flags & 0b00000011)

    return {
        'header_type': header_type,
        'context_flag': context_flag,
        'transport_type': transport_type,
        'destination_type': destination_type,
        'packet_type': packet_type
    }


def cmd_packet_pack(params):
    """Pack a packet into raw bytes."""
    import struct

    header_type = int(params['header_type'])
    context_flag = int(params['context_flag'])
    transport_type = int(params['transport_type'])
    destination_type = int(params['destination_type'])
    packet_type = int(params['packet_type'])
    hops = int(params.get('hops', 0))
    destination_hash = hex_to_bytes(params['destination_hash'])
    transport_id = hex_to_bytes(params['transport_id']) if params.get('transport_id') else None
    context = int(params.get('context', 0))
    data = hex_to_bytes(params['data'])

    # Compute flags
    flags = (header_type << 6) | (context_flag << 5) | (transport_type << 4) | (destination_type << 2) | packet_type

    # Build header
    header = struct.pack("!B", flags) + struct.pack("!B", hops)

    if header_type == 1:  # HEADER_2
        if transport_id is None:
            raise ValueError("HEADER_2 requires transport_id")
        header += transport_id + destination_hash
    else:  # HEADER_1
        header += destination_hash

    header += bytes([context])

    raw = header + data

    return {
        'raw': bytes_to_hex(raw),
        'header': bytes_to_hex(header),
        'size': len(raw)
    }


def cmd_packet_unpack(params):
    """Unpack raw packet bytes into components."""
    raw = hex_to_bytes(params['raw'])

    flags = raw[0]
    hops = raw[1]

    header_type = (flags & 0b01000000) >> 6
    context_flag = (flags & 0b00100000) >> 5
    transport_type = (flags & 0b00010000) >> 4
    destination_type = (flags & 0b00001100) >> 2
    packet_type = (flags & 0b00000011)

    DST_LEN = 16  # TRUNCATED_HASHLENGTH // 8

    if header_type == 1:  # HEADER_2
        transport_id = raw[2:2+DST_LEN]
        destination_hash = raw[2+DST_LEN:2+2*DST_LEN]
        context = raw[2+2*DST_LEN]
        data = raw[3+2*DST_LEN:]
    else:  # HEADER_1
        transport_id = None
        destination_hash = raw[2:2+DST_LEN]
        context = raw[2+DST_LEN]
        data = raw[3+DST_LEN:]

    return {
        'flags': flags,
        'hops': hops,
        'header_type': header_type,
        'context_flag': context_flag,
        'transport_type': transport_type,
        'destination_type': destination_type,
        'packet_type': packet_type,
        'transport_id': bytes_to_hex(transport_id) if transport_id else None,
        'destination_hash': bytes_to_hex(destination_hash),
        'context': context,
        'data': bytes_to_hex(data)
    }


def cmd_packet_hash(params):
    """Compute packet hash from raw bytes."""
    raw = hex_to_bytes(params['raw'])

    flags = raw[0]
    header_type = (flags & 0b01000000) >> 6

    DST_LEN = 16

    # Mask flags to only keep lower 4 bits
    masked_flags = bytes([flags & 0b00001111])

    if header_type == 1:  # HEADER_2
        # Skip transport_id (16 bytes after hops)
        hashable_part = masked_flags + raw[2+DST_LEN:]
    else:  # HEADER_1
        hashable_part = masked_flags + raw[2:]

    full_hash = hashlib.sha256(hashable_part).digest()
    truncated = full_hash[:16]

    return {
        'hash': bytes_to_hex(full_hash),
        'truncated_hash': bytes_to_hex(truncated),
        'hashable_part': bytes_to_hex(hashable_part)
    }


# Command dispatcher
COMMANDS = {
    'x25519_generate': cmd_x25519_generate,
    'x25519_public_from_private': cmd_x25519_public_from_private,
    'x25519_exchange': cmd_x25519_exchange,
    'ed25519_generate': cmd_ed25519_generate,
    'ed25519_sign': cmd_ed25519_sign,
    'ed25519_verify': cmd_ed25519_verify,
    'sha256': cmd_sha256,
    'sha512': cmd_sha512,
    'hmac_sha256': cmd_hmac_sha256,
    'hkdf': cmd_hkdf,
    'pkcs7_pad': cmd_pkcs7_pad,
    'pkcs7_unpad': cmd_pkcs7_unpad,
    'aes_encrypt': cmd_aes_encrypt,
    'aes_decrypt': cmd_aes_decrypt,
    'token_encrypt': cmd_token_encrypt,
    'token_decrypt': cmd_token_decrypt,
    'token_verify_hmac': cmd_token_verify_hmac,
    'identity_from_private_key': cmd_identity_from_private_key,
    'identity_encrypt': cmd_identity_encrypt,
    'identity_decrypt': cmd_identity_decrypt,
    'identity_sign': cmd_identity_sign,
    'identity_verify': cmd_identity_verify,
    'identity_hash': cmd_identity_hash,
    'destination_hash': cmd_destination_hash,
    'truncated_hash': cmd_truncated_hash,
    'name_hash': cmd_name_hash,
    'packet_flags': cmd_packet_flags,
    'packet_parse_flags': cmd_packet_parse_flags,
    'packet_pack': cmd_packet_pack,
    'packet_unpack': cmd_packet_unpack,
    'packet_hash': cmd_packet_hash,
}


def handle_request(request):
    """Process a single request and return response."""
    req_id = request.get('id', 'unknown')
    command = request.get('command')
    params = request.get('params', {})

    if command not in COMMANDS:
        return {
            'id': req_id,
            'success': False,
            'error': f"Unknown command: {command}"
        }

    try:
        result = COMMANDS[command](params)
        return {
            'id': req_id,
            'success': True,
            'result': result
        }
    except Exception as e:
        return {
            'id': req_id,
            'success': False,
            'error': f"{type(e).__name__}: {str(e)}",
            'traceback': traceback.format_exc()
        }


def main():
    """Main server loop."""
    # Signal ready
    print("READY", flush=True)

    # Process commands
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue

        try:
            request = json.loads(line)
            response = handle_request(request)
            print(json.dumps(response), flush=True)
        except json.JSONDecodeError as e:
            error_response = {
                'id': 'parse_error',
                'success': False,
                'error': f"JSON parse error: {e}"
            }
            print(json.dumps(error_response), flush=True)


if __name__ == '__main__':
    main()
