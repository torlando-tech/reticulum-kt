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
import multiprocessing

# Patch multiprocessing.set_start_method to be no-op after first call
# This is needed because LXMF.LXStamper calls it without force=True,
# which fails on Python 3.14+ if context is already set
_original_set_start_method = multiprocessing.set_start_method
_start_method_set = False

def _patched_set_start_method(method, force=False):
    global _start_method_set
    if _start_method_set and not force:
        return  # Silently ignore if already set
    _original_set_start_method(method, force=True)
    _start_method_set = True

multiprocessing.set_start_method = _patched_set_start_method
multiprocessing.set_start_method("fork")  # Pre-set to fork

# Add RNS Cryptography to path directly (bypass RNS __init__.py)
rns_path = os.environ.get('PYTHON_RNS_PATH', '../../../Reticulum')
# Convert to absolute path
rns_path = os.path.abspath(rns_path)
sys.path.insert(0, os.path.join(rns_path, 'RNS', 'Cryptography'))
sys.path.insert(0, rns_path)

# Add LXMF to path
lxmf_path = os.path.abspath(os.environ.get('PYTHON_LXMF_PATH', '../../../LXMF'))
sys.path.insert(0, lxmf_path)

import hashlib

# Import umsgpack from RNS vendor
sys.path.insert(0, os.path.join(rns_path, 'RNS', 'vendor'))
import umsgpack

# Import LXMF stamper (used for stamp generation/validation)
import LXMF.LXStamper as LXStamper

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


# HDLC framing constants and functions
HDLC_FLAG = 0x7E
HDLC_ESC = 0x7D
HDLC_ESC_MASK = 0x20


def cmd_hdlc_escape(params):
    """Escape data for HDLC framing."""
    data = hex_to_bytes(params['data'])

    escaped = data.replace(bytes([HDLC_ESC]), bytes([HDLC_ESC, HDLC_ESC ^ HDLC_ESC_MASK]))
    escaped = escaped.replace(bytes([HDLC_FLAG]), bytes([HDLC_ESC, HDLC_FLAG ^ HDLC_ESC_MASK]))

    return {
        'escaped': bytes_to_hex(escaped)
    }


def cmd_hdlc_frame(params):
    """Frame data with HDLC framing."""
    data = hex_to_bytes(params['data'])

    escaped = data.replace(bytes([HDLC_ESC]), bytes([HDLC_ESC, HDLC_ESC ^ HDLC_ESC_MASK]))
    escaped = escaped.replace(bytes([HDLC_FLAG]), bytes([HDLC_ESC, HDLC_FLAG ^ HDLC_ESC_MASK]))

    framed = bytes([HDLC_FLAG]) + escaped + bytes([HDLC_FLAG])

    return {
        'framed': bytes_to_hex(framed),
        'escaped': bytes_to_hex(escaped)
    }


# KISS framing constants and functions
KISS_FEND = 0xC0
KISS_FESC = 0xDB
KISS_TFEND = 0xDC
KISS_TFESC = 0xDD
KISS_CMD_DATA = 0x00


def cmd_kiss_escape(params):
    """Escape data for KISS framing."""
    data = hex_to_bytes(params['data'])

    escaped = data.replace(bytes([KISS_FESC]), bytes([KISS_FESC, KISS_TFESC]))
    escaped = escaped.replace(bytes([KISS_FEND]), bytes([KISS_FESC, KISS_TFEND]))

    return {
        'escaped': bytes_to_hex(escaped)
    }


def cmd_kiss_frame(params):
    """Frame data with KISS framing."""
    data = hex_to_bytes(params['data'])
    command = int(params.get('command', KISS_CMD_DATA))

    escaped = data.replace(bytes([KISS_FESC]), bytes([KISS_FESC, KISS_TFESC]))
    escaped = escaped.replace(bytes([KISS_FEND]), bytes([KISS_FESC, KISS_TFEND]))

    framed = bytes([KISS_FEND, command]) + escaped + bytes([KISS_FEND])

    return {
        'framed': bytes_to_hex(framed),
        'escaped': bytes_to_hex(escaped)
    }


# Link operations

def cmd_link_id_from_packet(params):
    """Compute link ID from link request packet data.

    Link ID = truncated_hash(hashable_part of packet)
    where hashable_part excludes transport_id and masks flags.
    For link requests, we also exclude MTU signalling bytes if present.
    """
    raw = hex_to_bytes(params['raw'])

    flags = raw[0]
    header_type = (flags & 0b01000000) >> 6

    DST_LEN = 16
    ECPUBSIZE = 64  # X25519 (32) + Ed25519 (32) public keys

    # Mask flags to only keep lower 4 bits
    masked_flags = bytes([flags & 0b00001111])

    if header_type == 1:  # HEADER_2
        hashable_part = masked_flags + raw[2+DST_LEN:]
    else:  # HEADER_1
        hashable_part = masked_flags + raw[2:]

    # For link requests, if data is longer than ECPUBSIZE,
    # exclude the extra bytes (MTU signalling) from hash
    # Parse to find data portion
    if header_type == 1:
        header_len = 2 + DST_LEN + DST_LEN + 1  # flags + hops + transport_id + dest + context
    else:
        header_len = 2 + DST_LEN + 1  # flags + hops + dest + context

    data_start = header_len
    data = raw[data_start:]

    if len(data) > ECPUBSIZE:
        diff = len(data) - ECPUBSIZE
        hashable_part = hashable_part[:-diff]

    full_hash = hashlib.sha256(hashable_part).digest()
    link_id = full_hash[:16]

    return {
        'link_id': bytes_to_hex(link_id),
        'hashable_part': bytes_to_hex(hashable_part)
    }


def cmd_link_derive_key(params):
    """Derive link encryption keys using HKDF.

    For AES-256-CBC mode, derives 64 bytes:
    - First 32 bytes: encryption key
    - Last 32 bytes: signing key

    Note: Link.get_context() returns None in Python RNS, so context is None.
    """
    shared_key = hex_to_bytes(params['shared_key'])
    link_id = hex_to_bytes(params['link_id'])
    mode = params.get('mode', 'AES_256_CBC')

    # Salt is the link ID (from Link.get_salt())
    salt = link_id

    # Context is None (from Link.get_context())
    context = None

    # Derived key length depends on mode
    if mode == 'AES_256_CBC':
        length = 64
    else:
        length = 32

    derived = HKDF.hkdf(
        length=length,
        derive_from=shared_key,
        salt=salt,
        context=context
    )

    return {
        'derived_key': bytes_to_hex(derived),
        'encryption_key': bytes_to_hex(derived[:32]) if length >= 64 else bytes_to_hex(derived[:16]),
        'signing_key': bytes_to_hex(derived[32:]) if length >= 64 else bytes_to_hex(derived[16:])
    }


def cmd_link_encrypt(params):
    """Encrypt data for transmission over a link using Token encryption."""
    derived_key = hex_to_bytes(params['derived_key'])
    plaintext = hex_to_bytes(params['plaintext'])
    iv = hex_to_bytes(params['iv']) if params.get('iv') else None

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

    return {
        'ciphertext': bytes_to_hex(token_bytes)
    }


def cmd_link_decrypt(params):
    """Decrypt data received over a link."""
    derived_key = hex_to_bytes(params['derived_key'])
    ciphertext = hex_to_bytes(params['ciphertext'])

    token_obj = Token.Token(derived_key)
    plaintext = token_obj.decrypt(ciphertext)

    return {
        'plaintext': bytes_to_hex(plaintext)
    }


def cmd_link_prove(params):
    """Generate link proof signature.

    The proof signs: link_id + receiver_pub + receiver_sig_pub + signalling_bytes
    """
    identity_private = hex_to_bytes(params['identity_private'])  # 64 bytes: X25519 + Ed25519
    link_id = hex_to_bytes(params['link_id'])
    receiver_pub = hex_to_bytes(params['receiver_pub'])  # X25519 public
    receiver_sig_pub = hex_to_bytes(params['receiver_sig_pub'])  # Ed25519 public
    signalling_bytes = hex_to_bytes(params.get('signalling_bytes', ''))

    # Build signed data
    signed_data = link_id + receiver_pub + receiver_sig_pub + signalling_bytes

    # Sign with Ed25519 private key (second 32 bytes of identity)
    ed25519_prv = identity_private[32:64]

    from pure25519.ed25519_oop import SigningKey
    sk = SigningKey(ed25519_prv)
    signature = sk.sign(signed_data)

    return {
        'signature': bytes_to_hex(signature),
        'signed_data': bytes_to_hex(signed_data)
    }


def cmd_link_verify_proof(params):
    """Verify link proof signature."""
    identity_public = hex_to_bytes(params['identity_public'])  # 64 bytes: X25519 + Ed25519
    link_id = hex_to_bytes(params['link_id'])
    receiver_pub = hex_to_bytes(params['receiver_pub'])
    receiver_sig_pub = hex_to_bytes(params['receiver_sig_pub'])
    signalling_bytes = hex_to_bytes(params.get('signalling_bytes', ''))
    signature = hex_to_bytes(params['signature'])

    # Build signed data
    signed_data = link_id + receiver_pub + receiver_sig_pub + signalling_bytes

    # Verify with Ed25519 public key (second 32 bytes of identity)
    ed25519_pub = identity_public[32:64]

    from pure25519.ed25519_oop import VerifyingKey
    try:
        vk = VerifyingKey(ed25519_pub)
        vk.verify(signature, signed_data)
        return {'valid': True}
    except Exception:
        return {'valid': False}


def cmd_link_signalling_bytes(params):
    """Encode MTU and mode into signalling bytes.

    Format: 3 bytes
    - Bits 21-23: mode (3 bits)
    - Bits 0-20: MTU (21 bits)
    """
    mtu = int(params['mtu'])
    mode = int(params.get('mode', 1))  # Default: AES_256_CBC = 0x01

    MTU_BYTEMASK = 0x1FFFFF  # 21 bits
    MODE_BYTEMASK = 0xE0  # Top 3 bits of first byte

    value = (mtu & MTU_BYTEMASK) + (((mode << 5) & MODE_BYTEMASK) << 16)

    signalling = bytes([
        (value >> 16) & 0xFF,
        (value >> 8) & 0xFF,
        value & 0xFF
    ])

    return {
        'signalling_bytes': bytes_to_hex(signalling),
        'decoded_mtu': mtu & MTU_BYTEMASK,
        'decoded_mode': mode
    }


def cmd_link_parse_signalling(params):
    """Parse signalling bytes into MTU and mode."""
    signalling = hex_to_bytes(params['signalling_bytes'])

    if len(signalling) != 3:
        raise ValueError(f"Signalling bytes must be 3 bytes, got {len(signalling)}")

    MTU_BYTEMASK = 0x1FFFFF
    MODE_BYTEMASK = 0xE0

    value = (signalling[0] << 16) | (signalling[1] << 8) | signalling[2]
    mtu = value & MTU_BYTEMASK
    mode = (signalling[0] & MODE_BYTEMASK) >> 5

    return {
        'mtu': mtu,
        'mode': mode
    }


# Announce operations

def cmd_random_hash(params):
    """Generate random_hash (5 random bytes + 5 timestamp bytes).

    Format matches Python RNS:
    random_hash = RNS.Identity.get_random_hash()[0:5]+int(time.time()).to_bytes(5, "big")
    """
    import time
    timestamp = params.get('timestamp')
    random_bytes = hex_to_bytes(params['random_bytes']) if params.get('random_bytes') else None

    # Generate random bytes if not provided
    if random_bytes is None:
        random_bytes = os.urandom(5)
    elif len(random_bytes) != 5:
        raise ValueError(f"random_bytes must be 5 bytes, got {len(random_bytes)}")

    # Use provided timestamp or current time
    if timestamp is not None:
        timestamp = int(timestamp)
    else:
        timestamp = int(time.time())

    # Combine: 5 random bytes + 5 timestamp bytes (big-endian)
    random_hash = random_bytes + timestamp.to_bytes(5, "big")

    return {
        'random_hash': bytes_to_hex(random_hash),
        'random_bytes': bytes_to_hex(random_bytes),
        'timestamp': timestamp,
        'timestamp_bytes': bytes_to_hex(timestamp.to_bytes(5, "big"))
    }


def cmd_announce_pack(params):
    """Pack announce payload.

    Without ratchet (148 bytes min):
      public_key (64) + name_hash (10) + random_hash (10) + signature (64) + app_data (var)

    With ratchet (180 bytes min):
      public_key (64) + name_hash (10) + random_hash (10) + ratchet (32) + signature (64) + app_data (var)
    """
    public_key = hex_to_bytes(params['public_key'])
    name_hash = hex_to_bytes(params['name_hash'])
    random_hash = hex_to_bytes(params['random_hash'])
    ratchet = hex_to_bytes(params['ratchet']) if params.get('ratchet') else b""
    signature = hex_to_bytes(params['signature'])
    app_data = hex_to_bytes(params['app_data']) if params.get('app_data') else b""

    # Validate sizes
    if len(public_key) != 64:
        raise ValueError(f"public_key must be 64 bytes, got {len(public_key)}")
    if len(name_hash) != 10:
        raise ValueError(f"name_hash must be 10 bytes, got {len(name_hash)}")
    if len(random_hash) != 10:
        raise ValueError(f"random_hash must be 10 bytes, got {len(random_hash)}")
    if ratchet and len(ratchet) != 32:
        raise ValueError(f"ratchet must be 32 bytes, got {len(ratchet)}")
    if len(signature) != 64:
        raise ValueError(f"signature must be 64 bytes, got {len(signature)}")

    # Pack announce data
    announce_data = public_key + name_hash + random_hash + ratchet + signature + app_data

    return {
        'announce_data': bytes_to_hex(announce_data),
        'size': len(announce_data),
        'has_ratchet': len(ratchet) > 0
    }


def cmd_announce_unpack(params):
    """Unpack announce payload.

    Returns all components of the announce.
    """
    announce_data = hex_to_bytes(params['announce_data'])
    has_ratchet = params.get('has_ratchet', False)

    # Constants
    KEYSIZE = 64
    NAME_HASH_LEN = 10
    RANDOM_HASH_LEN = 10
    RATCHET_SIZE = 32
    SIG_LEN = 64

    # Unpack based on whether ratchet is present
    if has_ratchet:
        if len(announce_data) < KEYSIZE + NAME_HASH_LEN + RANDOM_HASH_LEN + RATCHET_SIZE + SIG_LEN:
            raise ValueError(f"announce_data too short for ratchet announce: {len(announce_data)} bytes")

        public_key = announce_data[0:KEYSIZE]
        name_hash = announce_data[KEYSIZE:KEYSIZE+NAME_HASH_LEN]
        random_hash = announce_data[KEYSIZE+NAME_HASH_LEN:KEYSIZE+NAME_HASH_LEN+RANDOM_HASH_LEN]
        ratchet = announce_data[KEYSIZE+NAME_HASH_LEN+RANDOM_HASH_LEN:KEYSIZE+NAME_HASH_LEN+RANDOM_HASH_LEN+RATCHET_SIZE]
        signature = announce_data[KEYSIZE+NAME_HASH_LEN+RANDOM_HASH_LEN+RATCHET_SIZE:KEYSIZE+NAME_HASH_LEN+RANDOM_HASH_LEN+RATCHET_SIZE+SIG_LEN]
        app_data = announce_data[KEYSIZE+NAME_HASH_LEN+RANDOM_HASH_LEN+RATCHET_SIZE+SIG_LEN:]
    else:
        if len(announce_data) < KEYSIZE + NAME_HASH_LEN + RANDOM_HASH_LEN + SIG_LEN:
            raise ValueError(f"announce_data too short for non-ratchet announce: {len(announce_data)} bytes")

        public_key = announce_data[0:KEYSIZE]
        name_hash = announce_data[KEYSIZE:KEYSIZE+NAME_HASH_LEN]
        random_hash = announce_data[KEYSIZE+NAME_HASH_LEN:KEYSIZE+NAME_HASH_LEN+RANDOM_HASH_LEN]
        ratchet = b""
        signature = announce_data[KEYSIZE+NAME_HASH_LEN+RANDOM_HASH_LEN:KEYSIZE+NAME_HASH_LEN+RANDOM_HASH_LEN+SIG_LEN]
        app_data = announce_data[KEYSIZE+NAME_HASH_LEN+RANDOM_HASH_LEN+SIG_LEN:]

    return {
        'public_key': bytes_to_hex(public_key),
        'name_hash': bytes_to_hex(name_hash),
        'random_hash': bytes_to_hex(random_hash),
        'ratchet': bytes_to_hex(ratchet) if ratchet else '',
        'signature': bytes_to_hex(signature),
        'app_data': bytes_to_hex(app_data) if app_data else '',
        'has_ratchet': len(ratchet) > 0
    }


def cmd_announce_sign(params):
    """Sign announce data.

    Signed data format:
      destination_hash + public_key + name_hash + random_hash + ratchet + app_data
    """
    private_key = hex_to_bytes(params['private_key'])
    destination_hash = hex_to_bytes(params['destination_hash'])
    public_key = hex_to_bytes(params['public_key'])
    name_hash = hex_to_bytes(params['name_hash'])
    random_hash = hex_to_bytes(params['random_hash'])
    ratchet = hex_to_bytes(params['ratchet']) if params.get('ratchet') else b""
    app_data = hex_to_bytes(params['app_data']) if params.get('app_data') else b""

    # Build signed data (matches Python RNS Destination.announce())
    signed_data = destination_hash + public_key + name_hash + random_hash + ratchet + app_data

    # Sign with Ed25519 private key (second 32 bytes)
    ed25519_prv = private_key[32:64]

    from pure25519.ed25519_oop import SigningKey
    sk = SigningKey(ed25519_prv)
    signature = sk.sign(signed_data)

    return {
        'signature': bytes_to_hex(signature),
        'signed_data': bytes_to_hex(signed_data)
    }


def cmd_announce_verify(params):
    """Verify announce signature.

    Validates signature and optionally validates destination hash.
    """
    announce_data = hex_to_bytes(params['announce_data'])
    destination_hash = hex_to_bytes(params['destination_hash'])
    has_ratchet = params.get('has_ratchet', False)
    validate_dest_hash = params.get('validate_dest_hash', True)

    # Constants
    KEYSIZE = 64
    NAME_HASH_LEN = 10
    RANDOM_HASH_LEN = 10
    RATCHET_SIZE = 32
    SIG_LEN = 64

    # Unpack announce data
    if has_ratchet:
        public_key = announce_data[0:KEYSIZE]
        name_hash = announce_data[KEYSIZE:KEYSIZE+NAME_HASH_LEN]
        random_hash = announce_data[KEYSIZE+NAME_HASH_LEN:KEYSIZE+NAME_HASH_LEN+RANDOM_HASH_LEN]
        ratchet = announce_data[KEYSIZE+NAME_HASH_LEN+RANDOM_HASH_LEN:KEYSIZE+NAME_HASH_LEN+RANDOM_HASH_LEN+RATCHET_SIZE]
        signature = announce_data[KEYSIZE+NAME_HASH_LEN+RANDOM_HASH_LEN+RATCHET_SIZE:KEYSIZE+NAME_HASH_LEN+RANDOM_HASH_LEN+RATCHET_SIZE+SIG_LEN]
        app_data = announce_data[KEYSIZE+NAME_HASH_LEN+RANDOM_HASH_LEN+RATCHET_SIZE+SIG_LEN:]
    else:
        public_key = announce_data[0:KEYSIZE]
        name_hash = announce_data[KEYSIZE:KEYSIZE+NAME_HASH_LEN]
        random_hash = announce_data[KEYSIZE+NAME_HASH_LEN:KEYSIZE+NAME_HASH_LEN+RANDOM_HASH_LEN]
        ratchet = b""
        signature = announce_data[KEYSIZE+NAME_HASH_LEN+RANDOM_HASH_LEN:KEYSIZE+NAME_HASH_LEN+RANDOM_HASH_LEN+SIG_LEN]
        app_data = announce_data[KEYSIZE+NAME_HASH_LEN+RANDOM_HASH_LEN+SIG_LEN:]

    # Build signed data
    signed_data = destination_hash + public_key + name_hash + random_hash + ratchet + app_data

    # Verify signature
    ed25519_pub = public_key[32:64]

    from pure25519.ed25519_oop import VerifyingKey
    try:
        vk = VerifyingKey(ed25519_pub)
        vk.verify(signature, signed_data)
        signature_valid = True
    except Exception:
        signature_valid = False

    # Optionally validate destination hash
    dest_hash_valid = True
    expected_dest_hash = b""
    if validate_dest_hash:
        # Compute identity hash from public key
        identity_hash = hashlib.sha256(public_key).digest()[:16]

        # Compute expected destination hash
        hash_material = name_hash + identity_hash
        expected_dest_hash = hashlib.sha256(hash_material).digest()[:16]

        dest_hash_valid = (destination_hash == expected_dest_hash)

    return {
        'valid': signature_valid and dest_hash_valid,
        'signature_valid': signature_valid,
        'dest_hash_valid': dest_hash_valid,
        'expected_dest_hash': bytes_to_hex(expected_dest_hash) if validate_dest_hash else ''
    }


# Ratchet operations

def cmd_ratchet_id(params):
    """Compute ratchet ID from ratchet public key.

    Ratchet ID = SHA256(ratchet_public_bytes)[:10]
    """
    ratchet_public = hex_to_bytes(params['ratchet_public'])

    # Compute full hash
    full_hash = hashlib.sha256(ratchet_public).digest()

    # Truncate to NAME_HASH_LENGTH (80 bits = 10 bytes)
    ratchet_id = full_hash[:10]

    return {
        'ratchet_id': bytes_to_hex(ratchet_id),
        'full_hash': bytes_to_hex(full_hash)
    }


def cmd_ratchet_public_from_private(params):
    """Derive public key from ratchet private key."""
    ratchet_private = hex_to_bytes(params['ratchet_private'])

    # Create X25519 key pair from private key
    ratchet_prv = X25519.X25519PrivateKey.from_private_bytes(ratchet_private)
    ratchet_pub = ratchet_prv.public_key()
    ratchet_pub_bytes = ratchet_pub.public_bytes()

    return {
        'ratchet_public': bytes_to_hex(ratchet_pub_bytes)
    }


def cmd_ratchet_derive_key(params):
    """Derive encryption key using ratchet.

    This performs ECDH with ephemeral key and ratchet public key,
    then derives a key using HKDF with identity hash as salt.
    """
    ephemeral_private = hex_to_bytes(params['ephemeral_private'])
    ratchet_public = hex_to_bytes(params['ratchet_public'])
    identity_hash = hex_to_bytes(params['identity_hash'])

    # Create keys
    ephemeral_prv = X25519.X25519PrivateKey.from_private_bytes(ephemeral_private)
    ratchet_pub = X25519.X25519PublicKey.from_public_bytes(ratchet_public)

    # ECDH exchange
    shared_key = ephemeral_prv.exchange(ratchet_pub)

    # HKDF key derivation with identity hash as salt
    derived_key = HKDF.hkdf(
        length=64,
        derive_from=shared_key,
        salt=identity_hash,
        context=None
    )

    return {
        'shared_key': bytes_to_hex(shared_key),
        'derived_key': bytes_to_hex(derived_key)
    }


def cmd_ratchet_encrypt(params):
    """Encrypt plaintext using ratchet.

    Same as identity_encrypt but uses ratchet public key instead of identity public key.
    """
    ratchet_public = hex_to_bytes(params['ratchet_public'])
    plaintext = hex_to_bytes(params['plaintext'])
    identity_hash = hex_to_bytes(params['identity_hash'])
    ephemeral_private = hex_to_bytes(params['ephemeral_private']) if params.get('ephemeral_private') else None
    iv = hex_to_bytes(params['iv']) if params.get('iv') else None

    # Create ratchet public key
    ratchet_pub = X25519.X25519PublicKey.from_public_bytes(ratchet_public)

    # Generate or use provided ephemeral key
    if ephemeral_private:
        ephemeral_prv = X25519.X25519PrivateKey.from_private_bytes(ephemeral_private)
    else:
        ephemeral_prv = X25519.X25519PrivateKey.generate()

    ephemeral_pub_bytes = ephemeral_prv.public_key().public_bytes()

    # ECDH exchange with ratchet
    shared_key = ephemeral_prv.exchange(ratchet_pub)

    # HKDF key derivation with identity hash as salt
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
        'derived_key': bytes_to_hex(derived_key)
    }


def cmd_ratchet_decrypt(params):
    """Decrypt ciphertext using ratchet.

    Tries to decrypt using provided ratchet private keys.
    """
    ratchet_private = hex_to_bytes(params['ratchet_private'])
    ciphertext = hex_to_bytes(params['ciphertext'])
    identity_hash = hex_to_bytes(params['identity_hash'])

    # Extract ephemeral public key (first 32 bytes)
    if len(ciphertext) <= 32:
        return {
            'success': False,
            'plaintext': None,
            'error': 'Ciphertext too short'
        }

    ephemeral_pub_bytes = ciphertext[:32]
    token_data = ciphertext[32:]

    # Create keys
    ratchet_prv = X25519.X25519PrivateKey.from_private_bytes(ratchet_private)
    ephemeral_pub = X25519.X25519PublicKey.from_public_bytes(ephemeral_pub_bytes)

    # ECDH exchange
    shared_key = ratchet_prv.exchange(ephemeral_pub)

    # HKDF key derivation with identity hash as salt
    derived_key = HKDF.hkdf(
        length=64,
        derive_from=shared_key,
        salt=identity_hash,
        context=None
    )

    # Token decryption
    try:
        token_obj = Token.Token(derived_key)
        plaintext = token_obj.decrypt(token_data)

        if plaintext is None:
            return {
                'success': False,
                'plaintext': None,
                'shared_key': bytes_to_hex(shared_key),
                'derived_key': bytes_to_hex(derived_key)
            }

        return {
            'success': True,
            'plaintext': bytes_to_hex(plaintext),
            'shared_key': bytes_to_hex(shared_key),
            'derived_key': bytes_to_hex(derived_key)
        }
    except Exception as e:
        return {
            'success': False,
            'plaintext': None,
            'error': str(e),
            'shared_key': bytes_to_hex(shared_key),
            'derived_key': bytes_to_hex(derived_key)
        }


def cmd_ratchet_storage_format(params):
    """Pack ratchet storage format using msgpack.

    Format: {"ratchet": ratchet_bytes, "received": timestamp}
    """
    import time

    ratchet = hex_to_bytes(params['ratchet'])
    received = float(params.get('received', time.time()))

    # Pack using msgpack
    ratchet_data = {
        "ratchet": ratchet,
        "received": received
    }

    packed = umsgpack.packb(ratchet_data)

    return {
        'packed': bytes_to_hex(packed),
        'ratchet': bytes_to_hex(ratchet),
        'received': received
    }


def cmd_ratchet_extract_from_announce(params):
    """Extract ratchet from announce data.

    Announce format: public_key(64) + name_hash(10) + random_hash(10) + ratchet(32) + signature(64) [+ app_data]
    """
    announce_data = hex_to_bytes(params['announce_data'])

    # Parse announce structure
    # public_key: 64 bytes
    # name_hash: 10 bytes
    # random_hash: 10 bytes
    # ratchet: 32 bytes (or 0 if not present)
    # signature: 64 bytes

    if len(announce_data) < 64 + 10 + 10 + 64:
        return {
            'has_ratchet': False,
            'ratchet': None,
            'error': 'Announce too short'
        }

    offset = 0
    public_key = announce_data[offset:offset+64]
    offset += 64

    name_hash = announce_data[offset:offset+10]
    offset += 10

    random_hash = announce_data[offset:offset+10]
    offset += 10

    # Check if there's space for ratchet + signature
    remaining = len(announce_data) - offset

    if remaining >= 32 + 64:
        # Ratchet is present
        ratchet = announce_data[offset:offset+32]
        offset += 32
        signature = announce_data[offset:offset+64]
        offset += 64

        # Check if ratchet is all zeros (no ratchet)
        if ratchet == b'\x00' * 32:
            has_ratchet = False
            ratchet = None
        else:
            has_ratchet = True
    elif remaining == 64:
        # No ratchet, just signature
        has_ratchet = False
        ratchet = None
        signature = announce_data[offset:offset+64]
        offset += 64
    else:
        return {
            'has_ratchet': False,
            'ratchet': None,
            'error': 'Invalid announce structure'
        }

    # Any remaining data is app_data
    app_data = announce_data[offset:] if offset < len(announce_data) else b''

    result = {
        'has_ratchet': has_ratchet,
        'public_key': bytes_to_hex(public_key),
        'name_hash': bytes_to_hex(name_hash),
        'random_hash': bytes_to_hex(random_hash),
        'signature': bytes_to_hex(signature)
    }

    if has_ratchet:
        result['ratchet'] = bytes_to_hex(ratchet)
        # Compute ratchet ID
        ratchet_id = hashlib.sha256(ratchet).digest()[:10]
        result['ratchet_id'] = bytes_to_hex(ratchet_id)
    else:
        result['ratchet'] = None

    if app_data:
        result['app_data'] = bytes_to_hex(app_data)

    return result

# Ratchet operations

def cmd_ratchet_id(params):
    """Compute ratchet ID from ratchet public key.

    Ratchet ID = SHA256(ratchet_public_bytes)[:10]
    """
    ratchet_public = hex_to_bytes(params['ratchet_public'])

    # Compute full hash
    full_hash = hashlib.sha256(ratchet_public).digest()

    # Truncate to NAME_HASH_LENGTH (80 bits = 10 bytes)
    ratchet_id = full_hash[:10]

    return {
        'ratchet_id': bytes_to_hex(ratchet_id),
        'full_hash': bytes_to_hex(full_hash)
    }


def cmd_ratchet_public_from_private(params):
    """Derive public key from ratchet private key."""
    ratchet_private = hex_to_bytes(params['ratchet_private'])

    # Create X25519 key pair from private key
    ratchet_prv = X25519.X25519PrivateKey.from_private_bytes(ratchet_private)
    ratchet_pub = ratchet_prv.public_key()
    ratchet_pub_bytes = ratchet_pub.public_bytes()

    return {
        'ratchet_public': bytes_to_hex(ratchet_pub_bytes)
    }


def cmd_ratchet_derive_key(params):
    """Derive encryption key using ratchet.

    This performs ECDH with ephemeral key and ratchet public key,
    then derives a key using HKDF with identity hash as salt.
    """
    ephemeral_private = hex_to_bytes(params['ephemeral_private'])
    ratchet_public = hex_to_bytes(params['ratchet_public'])
    identity_hash = hex_to_bytes(params['identity_hash'])

    # Create keys
    ephemeral_prv = X25519.X25519PrivateKey.from_private_bytes(ephemeral_private)
    ratchet_pub = X25519.X25519PublicKey.from_public_bytes(ratchet_public)

    # ECDH exchange
    shared_key = ephemeral_prv.exchange(ratchet_pub)

    # HKDF key derivation with identity hash as salt
    derived_key = HKDF.hkdf(
        length=64,
        derive_from=shared_key,
        salt=identity_hash,
        context=None
    )

    return {
        'shared_key': bytes_to_hex(shared_key),
        'derived_key': bytes_to_hex(derived_key)
    }


def cmd_ratchet_encrypt(params):
    """Encrypt plaintext using ratchet.

    Same as identity_encrypt but uses ratchet public key instead of identity public key.
    """
    ratchet_public = hex_to_bytes(params['ratchet_public'])
    plaintext = hex_to_bytes(params['plaintext'])
    identity_hash = hex_to_bytes(params['identity_hash'])
    ephemeral_private = hex_to_bytes(params['ephemeral_private']) if params.get('ephemeral_private') else None
    iv = hex_to_bytes(params['iv']) if params.get('iv') else None

    # Create ratchet public key
    ratchet_pub = X25519.X25519PublicKey.from_public_bytes(ratchet_public)

    # Generate or use provided ephemeral key
    if ephemeral_private:
        ephemeral_prv = X25519.X25519PrivateKey.from_private_bytes(ephemeral_private)
    else:
        ephemeral_prv = X25519.X25519PrivateKey.generate()

    ephemeral_pub_bytes = ephemeral_prv.public_key().public_bytes()

    # ECDH exchange with ratchet
    shared_key = ephemeral_prv.exchange(ratchet_pub)

    # HKDF key derivation with identity hash as salt
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
        'derived_key': bytes_to_hex(derived_key)
    }


def cmd_ratchet_decrypt(params):
    """Decrypt ciphertext using ratchet.

    Tries to decrypt using provided ratchet private keys.
    """
    ratchet_private = hex_to_bytes(params['ratchet_private'])
    ciphertext = hex_to_bytes(params['ciphertext'])
    identity_hash = hex_to_bytes(params['identity_hash'])

    # Extract ephemeral public key (first 32 bytes)
    if len(ciphertext) <= 32:
        return {
            'success': False,
            'plaintext': None,
            'error': 'Ciphertext too short'
        }

    ephemeral_pub_bytes = ciphertext[:32]
    token_data = ciphertext[32:]

    # Create keys
    ratchet_prv = X25519.X25519PrivateKey.from_private_bytes(ratchet_private)
    ephemeral_pub = X25519.X25519PublicKey.from_public_bytes(ephemeral_pub_bytes)

    # ECDH exchange
    shared_key = ratchet_prv.exchange(ephemeral_pub)

    # HKDF key derivation with identity hash as salt
    derived_key = HKDF.hkdf(
        length=64,
        derive_from=shared_key,
        salt=identity_hash,
        context=None
    )

    # Token decryption
    try:
        token_obj = Token.Token(derived_key)
        plaintext = token_obj.decrypt(token_data)

        if plaintext is None:
            return {
                'success': False,
                'plaintext': None,
                'shared_key': bytes_to_hex(shared_key),
                'derived_key': bytes_to_hex(derived_key)
            }

        return {
            'success': True,
            'plaintext': bytes_to_hex(plaintext),
            'shared_key': bytes_to_hex(shared_key),
            'derived_key': bytes_to_hex(derived_key)
        }
    except Exception as e:
        return {
            'success': False,
            'plaintext': None,
            'error': str(e),
            'shared_key': bytes_to_hex(shared_key),
            'derived_key': bytes_to_hex(derived_key)
        }


def cmd_ratchet_storage_format(params):
    """Pack ratchet storage format using msgpack.

    Format: {"ratchet": ratchet_bytes, "received": timestamp}
    """
    import time

    ratchet = hex_to_bytes(params['ratchet'])
    received = float(params.get('received', time.time()))

    # Pack using msgpack
    ratchet_data = {
        "ratchet": ratchet,
        "received": received
    }

    packed = umsgpack.packb(ratchet_data)

    return {
        'packed': bytes_to_hex(packed),
        'ratchet': bytes_to_hex(ratchet),
        'received': received
    }


def cmd_ratchet_extract_from_announce(params):
    """Extract ratchet from announce data.

    Announce format: public_key(64) + name_hash(10) + random_hash(10) + ratchet(32) + signature(64) [+ app_data]
    """
    announce_data = hex_to_bytes(params['announce_data'])

    # Parse announce structure
    # public_key: 64 bytes
    # name_hash: 10 bytes
    # random_hash: 10 bytes
    # ratchet: 32 bytes (or 0 if not present)
    # signature: 64 bytes

    if len(announce_data) < 64 + 10 + 10 + 64:
        return {
            'has_ratchet': False,
            'ratchet': None,
            'error': 'Announce too short'
        }

    offset = 0
    public_key = announce_data[offset:offset+64]
    offset += 64

    name_hash = announce_data[offset:offset+10]
    offset += 10

    random_hash = announce_data[offset:offset+10]
    offset += 10

    # Check if there's space for ratchet + signature
    remaining = len(announce_data) - offset

    if remaining >= 32 + 64:
        # Ratchet is present
        ratchet = announce_data[offset:offset+32]
        offset += 32
        signature = announce_data[offset:offset+64]
        offset += 64

        # Check if ratchet is all zeros (no ratchet)
        if ratchet == b'\x00' * 32:
            has_ratchet = False
            ratchet = None
        else:
            has_ratchet = True
    elif remaining == 64:
        # No ratchet, just signature
        has_ratchet = False
        ratchet = None
        signature = announce_data[offset:offset+64]
        offset += 64
    else:
        return {
            'has_ratchet': False,
            'ratchet': None,
            'error': 'Invalid announce structure'
        }

    # Any remaining data is app_data
    app_data = announce_data[offset:] if offset < len(announce_data) else b''

    result = {
        'has_ratchet': has_ratchet,
        'public_key': bytes_to_hex(public_key),
        'name_hash': bytes_to_hex(name_hash),
        'random_hash': bytes_to_hex(random_hash),
        'signature': bytes_to_hex(signature)
    }

    if has_ratchet:
        result['ratchet'] = bytes_to_hex(ratchet)
        # Compute ratchet ID
        ratchet_id = hashlib.sha256(ratchet).digest()[:10]
        result['ratchet_id'] = bytes_to_hex(ratchet_id)
    else:
        result['ratchet'] = None

    if app_data:
        result['app_data'] = bytes_to_hex(app_data)

    return result


# Channel operations

def cmd_envelope_pack(params):
    """Pack a channel envelope.

    Format: [msgtype:2][sequence:2][length:2][data:N]
    All fields are big-endian.
    """
    import struct

    msgtype = int(params['msgtype'])
    sequence = int(params['sequence'])
    data = hex_to_bytes(params['data'])
    length = len(data)

    # Pack header: msgtype (2), sequence (2), length (2)
    header = struct.pack(">HHH", msgtype, sequence, length)
    envelope = header + data

    return {
        'envelope': bytes_to_hex(envelope),
        'msgtype': msgtype,
        'sequence': sequence,
        'length': length
    }


def cmd_envelope_unpack(params):
    """Unpack a channel envelope.

    Extracts msgtype, sequence, length, and data from envelope bytes.
    """
    import struct

    envelope = hex_to_bytes(params['envelope'])

    if len(envelope) < 6:
        raise ValueError(f"Envelope too short: {len(envelope)} bytes")

    # Unpack header
    msgtype, sequence, length = struct.unpack(">HHH", envelope[:6])
    data = envelope[6:]

    return {
        'msgtype': msgtype,
        'sequence': sequence,
        'length': length,
        'data': bytes_to_hex(data)
    }


def cmd_stream_msg_pack(params):
    """Pack a StreamDataMessage.

    Python format: [header:2][data:N]
    Header bits:
      Bit 15: EOF flag (0x8000)
      Bit 14: compressed flag (0x4000)
      Bits 13-0: stream_id (0-16383)
    """
    import struct

    stream_id = int(params['stream_id'])
    data = hex_to_bytes(params.get('data', ''))
    eof = bool(params.get('eof', False))
    compressed = bool(params.get('compressed', False))

    # Validate stream_id
    if stream_id < 0 or stream_id > 0x3FFF:  # 16383
        raise ValueError(f"stream_id must be 0-16383, got {stream_id}")

    # Build header value
    header_val = (stream_id & 0x3FFF)
    if eof:
        header_val |= 0x8000
    if compressed:
        header_val |= 0x4000

    # Pack as big-endian 2-byte header
    header = struct.pack(">H", header_val)
    message = header + data

    return {
        'message': bytes_to_hex(message),
        'header_val': header_val,
        'stream_id': stream_id,
        'eof': eof,
        'compressed': compressed
    }


def cmd_stream_msg_unpack(params):
    """Unpack a StreamDataMessage.

    Extracts stream_id, flags, and data from message bytes.
    """
    import struct

    message = hex_to_bytes(params['message'])

    if len(message) < 2:
        raise ValueError(f"Message too short: {len(message)} bytes")

    # Unpack header
    header_val = struct.unpack(">H", message[:2])[0]

    # Extract fields
    stream_id = header_val & 0x3FFF
    eof = (header_val & 0x8000) != 0
    compressed = (header_val & 0x4000) != 0
    data = message[2:]

    return {
        'stream_id': stream_id,
        'eof': eof,
        'compressed': compressed,
        'data': bytes_to_hex(data),
        'header_val': header_val
    }


# Link request/response operations

def cmd_link_rtt_pack(params):
    """Pack RTT value for link using umsgpack.

    Format: umsgpack.packb(rtt_float)
    """
    rtt = float(params['rtt'])
    packed = umsgpack.packb(rtt)

    return {
        'packed': bytes_to_hex(packed)
    }


def cmd_link_rtt_unpack(params):
    """Unpack RTT value from msgpack bytes."""
    packed = hex_to_bytes(params['packed'])
    rtt = umsgpack.unpackb(packed)

    return {
        'rtt': rtt
    }


def cmd_link_request_pack(params):
    """Pack link request data.

    Format: [timestamp, path_hash, data]
    - timestamp: float (time.time())
    - path_hash: bytes (16 bytes, truncated hash of path)
    - data: any (request payload)
    """
    timestamp = float(params['timestamp'])
    path_hash = hex_to_bytes(params['path_hash'])

    # data can be None, bytes, or other types
    if 'data' in params and params['data'] is not None:
        if isinstance(params['data'], str):
            # Assume hex-encoded bytes
            data = hex_to_bytes(params['data'])
        else:
            data = params['data']
    else:
        data = None

    unpacked_request = [timestamp, path_hash, data]
    packed = umsgpack.packb(unpacked_request)

    return {
        'packed': bytes_to_hex(packed)
    }


def cmd_link_request_unpack(params):
    """Unpack link request data.

    Returns timestamp, path_hash, and data.
    """
    packed = hex_to_bytes(params['packed'])
    unpacked = umsgpack.unpackb(packed)

    timestamp = unpacked[0]
    path_hash = unpacked[1]
    data = unpacked[2]

    return {
        'timestamp': timestamp,
        'path_hash': bytes_to_hex(path_hash),
        'data': bytes_to_hex(data) if data is not None and isinstance(data, bytes) else data
    }


def cmd_link_response_pack(params):
    """Pack link response data.

    Format: [request_id, response_data]
    - request_id: bytes (16 bytes, truncated hash of request packet)
    - response_data: any (response payload)
    """
    request_id = hex_to_bytes(params['request_id'])

    # response_data can be None, bytes, or other types
    if 'response_data' in params and params['response_data'] is not None:
        if isinstance(params['response_data'], str):
            # Assume hex-encoded bytes
            response_data = hex_to_bytes(params['response_data'])
        else:
            response_data = params['response_data']
    else:
        response_data = None

    packed_response = umsgpack.packb([request_id, response_data])

    return {
        'packed': bytes_to_hex(packed_response)
    }


def cmd_link_response_unpack(params):
    """Unpack link response data.

    Returns request_id and response_data.
    """
    packed = hex_to_bytes(params['packed'])
    unpacked = umsgpack.unpackb(packed)

    request_id = unpacked[0]
    response_data = unpacked[1]

    return {
        'request_id': bytes_to_hex(request_id),
        'response_data': bytes_to_hex(response_data) if response_data is not None and isinstance(response_data, bytes) else response_data
    }



# Transport operations

def cmd_path_entry_serialize(params):
    """Serialize a path table entry to msgpack format.

    Format: [destination_hash, timestamp, received_from, hops, expires, random_blobs, interface_hash, packet_hash]
    """
    destination_hash = hex_to_bytes(params['destination_hash'])
    timestamp = float(params['timestamp'])
    received_from = hex_to_bytes(params['received_from'])
    hops = int(params['hops'])
    expires = float(params['expires'])
    random_blobs = [hex_to_bytes(blob) for blob in params['random_blobs']]
    interface_hash = hex_to_bytes(params['interface_hash'])
    packet_hash = hex_to_bytes(params['packet_hash'])

    entry = [
        destination_hash,
        timestamp,
        received_from,
        hops,
        expires,
        random_blobs,
        interface_hash,
        packet_hash
    ]

    serialized = umsgpack.packb(entry)

    return {
        'serialized': bytes_to_hex(serialized)
    }


def cmd_path_entry_deserialize(params):
    """Deserialize a path table entry from msgpack format."""
    serialized = hex_to_bytes(params['serialized'])

    entry = umsgpack.unpackb(serialized)

    return {
        'destination_hash': bytes_to_hex(entry[0]),
        'timestamp': entry[1],
        'received_from': bytes_to_hex(entry[2]),
        'hops': entry[3],
        'expires': entry[4],
        'random_blobs': [bytes_to_hex(blob) for blob in entry[5]],
        'interface_hash': bytes_to_hex(entry[6]),
        'packet_hash': bytes_to_hex(entry[7])
    }


def cmd_path_request_pack(params):
    """Pack path request data.

    Format: [destination_hash:10-16][transport_instance:10-16?][tag:10?]
    - If transport_enabled: destination_hash + transport_instance + tag
    - Otherwise: destination_hash + tag
    """
    destination_hash = hex_to_bytes(params['destination_hash'])
    transport_instance = hex_to_bytes(params['transport_instance']) if params.get('transport_instance') else None
    tag = hex_to_bytes(params['tag']) if params.get('tag') else None

    if transport_instance:
        # Transport enabled: dest + transport + tag
        data = destination_hash + transport_instance
        if tag:
            data += tag
    else:
        # Transport disabled: dest + tag
        data = destination_hash
        if tag:
            data += tag

    return {
        'data': bytes_to_hex(data),
        'has_transport_instance': transport_instance is not None,
        'has_tag': tag is not None
    }


def cmd_path_request_unpack(params):
    """Unpack path request data.

    Extracts destination_hash, and optionally transport_instance and tag.
    """
    data = hex_to_bytes(params['data'])

    # Destination hash is first 10-16 bytes (we'll use 16 for truncated hash)
    destination_hash = data[:16]
    remaining = data[16:]

    transport_instance = None
    tag = None

    if len(remaining) >= 16:
        # Could be transport instance (16 bytes)
        transport_instance = remaining[:16]
        remaining = remaining[16:]

        if len(remaining) >= 10:
            # Remaining could be tag (10 bytes)
            tag = remaining[:10]
    elif len(remaining) >= 10:
        # No transport instance, but has tag
        tag = remaining[:10]

    result = {
        'destination_hash': bytes_to_hex(destination_hash)
    }

    if transport_instance:
        result['transport_instance'] = bytes_to_hex(transport_instance)
    if tag:
        result['tag'] = bytes_to_hex(tag)

    return result


def cmd_packet_hashlist_pack(params):
    """Pack a list of packet hashes for storage.

    Uses umsgpack to serialize a list of hashes.
    """
    hashes = [hex_to_bytes(h) for h in params['hashes']]

    serialized = umsgpack.packb(hashes)

    return {
        'serialized': bytes_to_hex(serialized),
        'count': len(hashes)
    }


def cmd_packet_hashlist_unpack(params):
    """Unpack a list of packet hashes from storage."""
    serialized = hex_to_bytes(params['serialized'])

    hashes = umsgpack.unpackb(serialized)

    return {
        'hashes': [bytes_to_hex(h) for h in hashes],
        'count': len(hashes)
    }


# Resource operations

def cmd_resource_adv_pack(params):
    """Pack a ResourceAdvertisement to msgpack bytes."""
    transfer_size = int(params['transfer_size'])
    data_size = int(params['data_size'])
    num_parts = int(params['num_parts'])
    resource_hash = hex_to_bytes(params['resource_hash'])
    random_hash = hex_to_bytes(params['random_hash'])
    original_hash = hex_to_bytes(params['original_hash']) if params.get('original_hash') else None
    segment_index = int(params['segment_index'])
    total_segments = int(params['total_segments'])
    request_id = hex_to_bytes(params['request_id']) if params.get('request_id') else None
    flags = int(params['flags'])
    hashmap = hex_to_bytes(params.get('hashmap', ''))
    segment = int(params.get('segment', 0))

    MAPHASH_LEN = 4
    HASHMAP_MAX_LEN = 56

    hashmap_start = segment * HASHMAP_MAX_LEN
    hashmap_end = min((segment + 1) * HASHMAP_MAX_LEN, num_parts)

    hashmap_slice = b""
    for i in range(hashmap_start, hashmap_end):
        start_byte = i * MAPHASH_LEN
        end_byte = (i + 1) * MAPHASH_LEN
        if end_byte <= len(hashmap):
            hashmap_slice += hashmap[start_byte:end_byte]

    dictionary = {
        "t": transfer_size,
        "d": data_size,
        "n": num_parts,
        "h": resource_hash,
        "r": random_hash,
        "o": original_hash,
        "i": segment_index,
        "l": total_segments,
        "q": request_id,
        "f": flags,
        "m": hashmap_slice
    }

    packed = umsgpack.packb(dictionary)

    return {
        'packed': bytes_to_hex(packed),
        'size': len(packed)
    }


def cmd_resource_adv_unpack(params):
    """Unpack a ResourceAdvertisement from msgpack bytes."""
    packed = hex_to_bytes(params['packed'])
    dictionary = umsgpack.unpackb(packed)

    flags = dictionary["f"]
    encrypted = (flags & 0x01) == 0x01
    compressed = ((flags >> 1) & 0x01) == 0x01
    split = ((flags >> 2) & 0x01) == 0x01
    is_request = ((flags >> 3) & 0x01) == 0x01
    is_response = ((flags >> 4) & 0x01) == 0x01
    has_metadata = ((flags >> 5) & 0x01) == 0x01

    return {
        'transfer_size': dictionary["t"],
        'data_size': dictionary["d"],
        'num_parts': dictionary["n"],
        'resource_hash': bytes_to_hex(dictionary["h"]),
        'random_hash': bytes_to_hex(dictionary["r"]),
        'original_hash': bytes_to_hex(dictionary["o"]) if dictionary["o"] is not None else None,
        'segment_index': dictionary["i"],
        'total_segments': dictionary["l"],
        'request_id': bytes_to_hex(dictionary["q"]) if dictionary["q"] is not None else None,
        'flags': flags,
        'hashmap': bytes_to_hex(dictionary["m"]),
        'encrypted': encrypted,
        'compressed': compressed,
        'split': split,
        'is_request': is_request,
        'is_response': is_response,
        'has_metadata': has_metadata
    }


def cmd_resource_hash(params):
    """Compute resource hash from data."""
    data = hex_to_bytes(params['data'])
    random_hash = hex_to_bytes(params['random_hash'])

    hash_material = random_hash + data
    full_hash = hashlib.sha256(hash_material).digest()
    truncated = full_hash[:16]

    return {
        'hash': bytes_to_hex(truncated),
        'full_hash': bytes_to_hex(full_hash)
    }


def cmd_resource_flags(params):
    """Encode or decode resource flags byte."""
    mode = params.get('mode', 'encode')

    if mode == 'encode':
        encrypted = bool(params.get('encrypted', False))
        compressed = bool(params.get('compressed', False))
        split = bool(params.get('split', False))
        is_request = bool(params.get('is_request', False))
        is_response = bool(params.get('is_response', False))
        has_metadata = bool(params.get('has_metadata', False))

        flags = 0x00
        if encrypted: flags |= 0x01
        if compressed: flags |= 0x02
        if split: flags |= 0x04
        if is_request: flags |= 0x08
        if is_response: flags |= 0x10
        if has_metadata: flags |= 0x20

        return {
            'flags': flags
        }
    else:
        flags = int(params['flags'])
        return {
            'encrypted': (flags & 0x01) == 0x01,
            'compressed': ((flags >> 1) & 0x01) == 0x01,
            'split': ((flags >> 2) & 0x01) == 0x01,
            'is_request': ((flags >> 3) & 0x01) == 0x01,
            'is_response': ((flags >> 4) & 0x01) == 0x01,
            'has_metadata': ((flags >> 5) & 0x01) == 0x01
        }


def cmd_hashmap_pack(params):
    """Pack a hashmap segment from part hashes."""
    parts = params.get('parts', [])
    start_index = int(params.get('start_index', 0))
    count = int(params.get('count', len(parts)))

    part_data_list = []
    if isinstance(parts, list):
        part_data_list = [hex_to_bytes(p) for p in parts]
    else:
        part_data_list = [hex_to_bytes(parts)]

    hashmap = b""
    for i in range(start_index, min(start_index + count, len(part_data_list))):
        part_data = part_data_list[i]
        part_hash = hashlib.sha256(part_data).digest()[:4]
        hashmap += part_hash

    return {
        'hashmap': bytes_to_hex(hashmap),
        'num_hashes': (len(hashmap) // 4)
    }


def cmd_resource_map_hash(params):
    """Compute map hash for a single resource part.

    Map hash = SHA256(part_data + random_hash)[:4]
    This matches Resource.get_map_hash() in Python RNS.
    """
    part_data = hex_to_bytes(params['part_data'])
    random_hash = hex_to_bytes(params['random_hash'])

    hash_material = part_data + random_hash
    map_hash = hashlib.sha256(hash_material).digest()[:4]

    return {
        'map_hash': bytes_to_hex(map_hash)
    }


def cmd_resource_build_hashmap(params):
    """Build complete hashmap from list of parts.

    Each part hash = SHA256(part_data + random_hash)[:4]
    Hashmap = concatenation of all part hashes.
    """
    parts = params.get('parts', [])
    random_hash = hex_to_bytes(params['random_hash'])

    part_data_list = [hex_to_bytes(p) for p in parts]

    hashmap = b""
    for part_data in part_data_list:
        hash_material = part_data + random_hash
        map_hash = hashlib.sha256(hash_material).digest()[:4]
        hashmap += map_hash

    return {
        'hashmap': bytes_to_hex(hashmap),
        'num_parts': len(part_data_list)
    }


def cmd_resource_proof(params):
    """Compute expected proof for resource completion.

    Expected proof = SHA256(uncompressed_data + resource_hash)[:16]
    This is what the receiver sends to prove successful receipt.
    """
    data = hex_to_bytes(params['data'])
    resource_hash = hex_to_bytes(params['resource_hash'])

    hash_material = data + resource_hash
    proof = hashlib.sha256(hash_material).digest()[:16]

    return {
        'proof': bytes_to_hex(proof)
    }


def cmd_resource_find_part(params):
    """Find part index in hashmap by its map hash.

    Searches hashmap for matching 4-byte hash and returns index.
    Returns index=-1 and found=False if not found.
    """
    hashmap = hex_to_bytes(params['hashmap'])
    map_hash = hex_to_bytes(params['map_hash'])

    if len(map_hash) != 4:
        return {'index': -1, 'found': False, 'error': 'map_hash must be 4 bytes'}

    for i in range(0, len(hashmap), 4):
        if hashmap[i:i+4] == map_hash:
            return {'index': i // 4, 'found': True}

    return {'index': -1, 'found': False}


# IFAC (Interface Access Code) operations
IFAC_SALT = bytes.fromhex("adf54d882c9a9b80771eb4995d702d4a3e733391b2a0f53f416d9f907e55cff8")


def cmd_ifac_derive_key(params):
    """Derive IFAC key from network name/passphrase.

    Uses HKDF with IFAC_SALT to derive a 64-byte key.
    """
    ifac_origin = hex_to_bytes(params['ifac_origin'])

    # Derive 64-byte key using HKDF (matches RNS interface authentication)
    ifac_key = HKDF.hkdf(length=64, derive_from=ifac_origin, salt=IFAC_SALT, context=None)

    return {
        'ifac_key': bytes_to_hex(ifac_key),
        'ifac_salt': bytes_to_hex(IFAC_SALT)
    }


def cmd_ifac_compute(params):
    """Compute IFAC tag for a packet.

    IFAC = last N bytes of Ed25519 signature of packet data.
    The 64-byte ifac_key contains: bytes 0-31 = X25519 key, bytes 32-63 = Ed25519 signing key
    """
    from pure25519.ed25519_oop import SigningKey

    ifac_key = hex_to_bytes(params['ifac_key'])
    packet_data = hex_to_bytes(params['packet_data'])
    ifac_size = int(params.get('ifac_size', 16))

    # Ed25519 signing key is the second half (bytes 32-63)
    ed25519_key = ifac_key[32:]
    sk = SigningKey(ed25519_key)

    # Sign the packet data
    signature = sk.sign(packet_data)

    # IFAC is the last ifac_size bytes of the signature
    ifac = signature[-ifac_size:]

    return {
        'ifac': bytes_to_hex(ifac),
        'signature': bytes_to_hex(signature)
    }


def cmd_ifac_verify(params):
    """Verify IFAC tag matches expected.

    Recomputes IFAC and compares.
    The 64-byte ifac_key contains: bytes 0-31 = X25519 key, bytes 32-63 = Ed25519 signing key
    """
    from pure25519.ed25519_oop import SigningKey

    ifac_key = hex_to_bytes(params['ifac_key'])
    packet_data = hex_to_bytes(params['packet_data'])
    expected_ifac = hex_to_bytes(params['expected_ifac'])
    ifac_size = len(expected_ifac)

    # Ed25519 signing key is the second half (bytes 32-63)
    ed25519_key = ifac_key[32:]
    sk = SigningKey(ed25519_key)

    # Sign the packet data
    signature = sk.sign(packet_data)

    # IFAC is the last ifac_size bytes of the signature
    computed_ifac = signature[-ifac_size:]

    return {
        'valid': computed_ifac == expected_ifac,
        'computed_ifac': bytes_to_hex(computed_ifac)
    }


# Compression operations
def cmd_bz2_compress(params):
    """Compress data using BZ2.

    Returns compressed data and compression ratio.
    """
    import bz2

    data = hex_to_bytes(params['data'])

    compressed = bz2.compress(data)

    return {
        'compressed': bytes_to_hex(compressed),
        'original_size': len(data),
        'compressed_size': len(compressed),
        'ratio': len(compressed) / len(data) if len(data) > 0 else 0
    }


def cmd_bz2_decompress(params):
    """Decompress BZ2 data.

    Returns decompressed data.
    """
    import bz2

    compressed = hex_to_bytes(params['compressed'])

    decompressed = bz2.decompress(compressed)

    return {
        'decompressed': bytes_to_hex(decompressed),
        'size': len(decompressed)
    }


# LXMF operations

def cmd_lxmf_pack(params):
    """Pack LXMF message from components.

    Follows LXMessage.pack() logic but works with raw hashes instead of Destination objects.
    Hash is computed WITHOUT stamp, then stamp can be added to payload separately.
    """
    destination_hash = hex_to_bytes(params['destination_hash'])
    source_hash = hex_to_bytes(params['source_hash'])
    timestamp = float(params['timestamp'])
    title = params.get('title', '')
    content = params.get('content', '')
    fields = params.get('fields', {})

    # Convert string field keys to int (JSON only supports string keys)
    if fields:
        fields = {int(k): v for k, v in fields.items()}

    # Encode title and content as bytes
    title_bytes = title.encode('utf-8') if isinstance(title, str) else title
    content_bytes = content.encode('utf-8') if isinstance(content, str) else content

    # Build payload: [timestamp, title, content, fields]
    payload = [timestamp, title_bytes, content_bytes, fields]
    packed_payload = umsgpack.packb(payload)

    # Compute hash: SHA256(dest_hash + source_hash + packed_payload)
    hashed_part = destination_hash + source_hash + packed_payload
    message_hash = hashlib.sha256(hashed_part).digest()

    # Signed part: hashed_part + hash
    signed_part = hashed_part + message_hash

    return {
        'packed_payload': bytes_to_hex(packed_payload),
        'hashed_part': bytes_to_hex(hashed_part),
        'message_hash': bytes_to_hex(message_hash),
        'signed_part': bytes_to_hex(signed_part)
    }


def cmd_lxmf_unpack(params):
    """Unpack LXMF message bytes to components.

    Follows LXMessage.unpack_from_bytes() logic.
    Extracts stamp from payload if present, recomputes hash without stamp.
    """
    lxmf_bytes = hex_to_bytes(params['lxmf_bytes'])

    DEST_LEN = 16  # LXMessage.DESTINATION_LENGTH
    SIG_LEN = 64   # LXMessage.SIGNATURE_LENGTH

    destination_hash = lxmf_bytes[:DEST_LEN]
    source_hash = lxmf_bytes[DEST_LEN:2*DEST_LEN]
    signature = lxmf_bytes[2*DEST_LEN:2*DEST_LEN+SIG_LEN]
    packed_payload = lxmf_bytes[2*DEST_LEN+SIG_LEN:]

    unpacked_payload = umsgpack.unpackb(packed_payload)

    # Extract stamp if present (5th element)
    stamp = None
    if len(unpacked_payload) > 4:
        stamp = unpacked_payload[4]
        unpacked_payload = unpacked_payload[:4]
        # Repack without stamp for hash computation
        packed_payload = umsgpack.packb(unpacked_payload)

    # Compute hash (always without stamp)
    hashed_part = destination_hash + source_hash + packed_payload
    message_hash = hashlib.sha256(hashed_part).digest()

    # Decode title/content
    title_bytes = unpacked_payload[1]
    content_bytes = unpacked_payload[2]
    title = title_bytes.decode('utf-8') if isinstance(title_bytes, bytes) else title_bytes
    content = content_bytes.decode('utf-8') if isinstance(content_bytes, bytes) else content_bytes

    return {
        'destination_hash': bytes_to_hex(destination_hash),
        'source_hash': bytes_to_hex(source_hash),
        'signature': bytes_to_hex(signature),
        'timestamp': unpacked_payload[0],
        'title': title,
        'content': content,
        'fields': unpacked_payload[3],
        'stamp': bytes_to_hex(stamp) if stamp else None,
        'message_hash': bytes_to_hex(message_hash)
    }


def serialize_field_value(value):
    """Recursively serialize field values for JSON transport.

    Binary data is hex-encoded with type annotation.
    Lists/tuples are recursively serialized.
    """
    if isinstance(value, bytes):
        return {'type': 'bytes', 'hex': value.hex()}
    elif isinstance(value, (list, tuple)):
        return {'type': 'list', 'items': [serialize_field_value(v) for v in value]}
    elif isinstance(value, dict):
        return {'type': 'dict', 'items': {str(k): serialize_field_value(v) for k, v in value.items()}}
    elif isinstance(value, int):
        return {'type': 'int', 'value': value}
    elif isinstance(value, float):
        return {'type': 'float', 'value': value}
    elif isinstance(value, str):
        return {'type': 'str', 'value': value}
    else:
        return {'type': type(value).__name__, 'value': str(value)}


def cmd_lxmf_unpack_with_fields(params):
    """Unpack LXMF message with deep field serialization.

    Like lxmf_unpack but returns fields_hex with all nested binary data hex-encoded.
    Replaces 'fields' with 'fields_hex' to ensure JSON serializability.
    """
    result = cmd_lxmf_unpack(params)

    # Serialize all field values for JSON transport
    fields = result.get('fields', {})
    fields_hex = {}
    if fields:
        for key, value in fields.items():
            fields_hex[str(key)] = serialize_field_value(value)

    # Replace fields with serialized version for JSON transport
    result['fields_hex'] = fields_hex
    del result['fields']  # Remove raw fields to avoid JSON serialization issues
    return result


def cmd_lxmf_hash(params):
    """Compute LXMF message hash from components.

    Hash = SHA256(destination_hash + source_hash + packed_payload)
    This is the message_id used for stamp generation.
    """
    destination_hash = hex_to_bytes(params['destination_hash'])
    source_hash = hex_to_bytes(params['source_hash'])
    timestamp = float(params['timestamp'])
    title = params.get('title', '')
    content = params.get('content', '')
    fields = params.get('fields', {})

    # Convert string field keys to int
    if fields:
        fields = {int(k): v for k, v in fields.items()}

    # Encode title and content
    title_bytes = title.encode('utf-8') if isinstance(title, str) else title
    content_bytes = content.encode('utf-8') if isinstance(content, str) else content

    # Build payload and hash
    payload = [timestamp, title_bytes, content_bytes, fields]
    packed_payload = umsgpack.packb(payload)
    hashed_part = destination_hash + source_hash + packed_payload
    message_hash = hashlib.sha256(hashed_part).digest()

    return {
        'message_hash': bytes_to_hex(message_hash)
    }


def cmd_lxmf_stamp_workblock(params):
    """Generate stamp workblock from message ID.

    Uses LXStamper.stamp_workblock() for exact Python compatibility.
    Default expand_rounds=3000 for standard LXMF stamps.
    """
    message_id = hex_to_bytes(params['message_id'])
    expand_rounds = int(params.get('expand_rounds', 3000))

    workblock = LXStamper.stamp_workblock(message_id, expand_rounds=expand_rounds)

    return {
        'workblock': bytes_to_hex(workblock),
        'size': len(workblock)
    }


def cmd_lxmf_stamp_valid(params):
    """Validate stamp against target cost and workblock.

    Uses LXStamper.stamp_valid() and stamp_value() for exact Python compatibility.
    """
    stamp = hex_to_bytes(params['stamp'])
    target_cost = int(params['target_cost'])
    workblock = hex_to_bytes(params['workblock'])

    valid = LXStamper.stamp_valid(stamp, target_cost, workblock)
    value = LXStamper.stamp_value(workblock, stamp) if valid else 0

    return {
        'valid': valid,
        'value': value
    }


def cmd_lxmf_stamp_generate(params):
    """Generate stamp meeting target cost.

    Uses LXStamper.generate_stamp() for exact Python compatibility.
    WARNING: Can be slow for high costs. Use expand_rounds=25 for quick tests.
    """
    message_id = hex_to_bytes(params['message_id'])
    stamp_cost = int(params['stamp_cost'])
    expand_rounds = int(params.get('expand_rounds', 3000))

    stamp, value = LXStamper.generate_stamp(message_id, stamp_cost, expand_rounds=expand_rounds)

    return {
        'stamp': bytes_to_hex(stamp) if stamp else None,
        'value': value
    }


# ============================================================================
# Live Reticulum/LXMF Networking Commands
# ============================================================================
# These commands start actual Reticulum instances and LXMF routers for
# end-to-end interoperability testing. Unlike the crypto-only commands above,
# these use the full RNS import and manage network state.

# Global state for live networking
_rns_instance = None
_lxmf_router = None
_lxmf_identity = None
_lxmf_destination = None
_received_messages = []
_rns_module = None  # Cached RNS module


def _get_full_rns():
    """Import full RNS module for networking.

    Returns the cached RNS module, or imports it if not already done.
    IMPORTANT: This clears ALL RNS-related modules and reimports cleanly.
    """
    global _rns_module

    if _rns_module is not None:
        return _rns_module

    import importlib
    import sys

    # Remove ALL RNS-related modules to get a clean slate
    # This includes fake modules (RNS_HMAC, etc.) and any partial imports
    modules_to_remove = [k for k in list(sys.modules.keys())
                         if k.startswith('RNS') or k.startswith('LXMF')]
    for mod in modules_to_remove:
        try:
            del sys.modules[mod]
        except KeyError:
            pass

    # Import real RNS fresh
    import RNS
    _rns_module = RNS
    return RNS


def cmd_rns_start(params):
    """Start Reticulum with TCP server interface.

    params:
        tcp_port (int): Port for TCP server interface
        config_path (str, optional): Config directory path (default: temp dir)

    Returns:
        identity_hash (hex): Hash of the transport identity
        ready (bool): True if started successfully
    """
    global _rns_instance

    import tempfile

    tcp_port = int(params['tcp_port'])
    config_path = params.get('config_path')

    if not config_path:
        config_path = tempfile.mkdtemp(prefix='rns_test_')

    # Get full RNS
    RNS = _get_full_rns()

    # Suppress RNS logging to avoid polluting JSON output on stdout
    # RNS logs go to stdout by default which breaks the bridge protocol
    RNS.loglevel = RNS.LOG_CRITICAL

    # Start Reticulum with transport enabled (minimal logging)
    _rns_instance = RNS.Reticulum(
        configdir=config_path,
        loglevel=RNS.LOG_CRITICAL  # Only log critical errors
    )

    # Create TCP server interface using configuration dict
    # This matches how RNS loads interfaces from config
    config = {
        "name": "TestTCPServer",
        "listen_ip": "127.0.0.1",
        "listen_port": tcp_port,
        "i2p_tunneled": False,
        "prefer_ipv6": False
    }

    tcp_interface = RNS.Interfaces.TCPInterface.TCPServerInterface(
        RNS.Transport,
        config
    )

    # Use _add_interface() to properly initialize all required attributes
    # This is the same method Reticulum uses when loading interfaces from config
    _rns_instance._add_interface(tcp_interface)

    # Get transport identity hash
    identity_hash = RNS.Transport.identity.hash if RNS.Transport.identity else b'\x00' * 16

    return {
        'identity_hash': bytes_to_hex(identity_hash),
        'ready': 'true',
        'config_path': config_path
    }


def cmd_rns_stop(params):
    """Stop Reticulum instance.

    Performs clean shutdown of Transport and interfaces.
    """
    global _rns_instance

    RNS = _get_full_rns()

    try:
        RNS.Transport.exit_handler()
    except:
        pass

    _rns_instance = None

    return {
        'stopped': True
    }


def cmd_lxmf_start_router(params):
    """Start LXMF router with delivery destination.

    params:
        identity_hex (str, optional): 64-byte private key hex (X25519 + Ed25519)
        display_name (str, optional): Display name for announcements

    Returns:
        identity_hash (hex): Hash of the router identity
        destination_hash (hex): Hash of the delivery destination
    """
    global _lxmf_router, _lxmf_identity, _lxmf_destination, _received_messages

    import tempfile

    identity_hex = params.get('identity_hex')
    display_name = params.get('display_name')

    RNS = _get_full_rns()
    import LXMF

    # Create or restore identity
    if identity_hex:
        private_key = hex_to_bytes(identity_hex)
        _lxmf_identity = RNS.Identity.from_bytes(private_key)
    else:
        _lxmf_identity = RNS.Identity()

    # Create storage path
    storage_path = tempfile.mkdtemp(prefix='lxmf_test_')

    # Create LXMF router
    _lxmf_router = LXMF.LXMRouter(
        identity=_lxmf_identity,
        storagepath=storage_path
    )

    # Register delivery identity
    _lxmf_destination = _lxmf_router.register_delivery_identity(
        _lxmf_identity,
        display_name=display_name
    )

    # Clear received messages
    _received_messages = []

    # Set delivery callback
    def delivery_callback(message):
        global _received_messages
        msg_data = {
            'source_hash': bytes_to_hex(message.source_hash),
            'destination_hash': bytes_to_hex(message.destination_hash),
            'content': message.content.decode('utf-8') if isinstance(message.content, bytes) else message.content,
            'title': message.title.decode('utf-8') if isinstance(message.title, bytes) else message.title,
            'timestamp': message.timestamp,
            'fields': {}
        }
        if hasattr(message, 'hash') and message.hash:
            msg_data['hash'] = bytes_to_hex(message.hash)
        if hasattr(message, 'fields') and message.fields:
            for k, v in message.fields.items():
                if isinstance(v, bytes):
                    msg_data['fields'][str(k)] = bytes_to_hex(v)
                else:
                    msg_data['fields'][str(k)] = v
        _received_messages.append(msg_data)

    _lxmf_router.register_delivery_callback(delivery_callback)

    return {
        'identity_hash': bytes_to_hex(_lxmf_identity.hash),
        'destination_hash': bytes_to_hex(_lxmf_destination.hash),
        'identity_public_key': bytes_to_hex(_lxmf_identity.get_public_key())
    }


def cmd_lxmf_get_messages(params):
    """Get received LXMF messages.

    Returns list of received messages with decoded content.
    """
    global _received_messages

    return {
        'messages': _received_messages,
        'count': len(_received_messages)
    }


def cmd_lxmf_clear_messages(params):
    """Clear received messages list."""
    global _received_messages
    _received_messages = []

    return {
        'cleared': True
    }


def cmd_lxmf_announce(params):
    """Announce the LXMF delivery destination.

    This makes the LXMF destination known on the network so other nodes
    can discover it and send messages.

    Returns:
        announced (bool): True if announced successfully
        destination_hash (hex): Hash of the announced destination
    """
    global _lxmf_router, _lxmf_destination

    if not _lxmf_router or not _lxmf_destination:
        return {
            'announced': False,
            'error': 'LXMF router not started'
        }

    # Announce the delivery destination
    _lxmf_router.announce(_lxmf_destination.hash)

    return {
        'announced': True,
        'destination_hash': bytes_to_hex(_lxmf_destination.hash)
    }


def cmd_lxmf_send_direct(params):
    """Send LXMF message via DIRECT delivery.

    params:
        destination_hash (hex): Destination hash (16 bytes)
        content (str): Message content
        title (str, optional): Message title
        fields (dict, optional): Additional fields

    Returns:
        sent (bool): True if message was queued
        message_hash (hex): Hash of the sent message
        status (str): Status of the send operation

    Note: This command requires that the destination has been announced
    and is known to the transport layer. For testing, announce the
    destination first before trying to send.
    """
    global _lxmf_router, _lxmf_identity, _lxmf_destination

    if not _lxmf_router:
        return {
            'sent': False,
            'status': 'error',
            'error': 'LXMF router not started'
        }

    RNS = _get_full_rns()
    import LXMF

    destination_hash = hex_to_bytes(params['destination_hash'])
    content = params['content']
    title = params.get('title', '')
    fields = params.get('fields', {})

    # Convert string field keys to int
    if fields:
        fields = {int(k): v for k, v in fields.items()}

    # Try to find the identity for this destination from recalled identities
    # This is needed because LXMF requires a proper Destination object
    identity = RNS.Identity.recall(destination_hash)
    if identity is None:
        # Check if we have a path at least
        has_path = RNS.Transport.has_path(destination_hash)
        return {
            'sent': False,
            'status': 'no_identity',
            'error': f'No identity recalled for destination {destination_hash.hex()}',
            'has_path': has_path
        }

    # Create an LXMF delivery destination from the recalled identity
    # LXMF uses "lxmf" app name and "delivery" aspect
    destination = RNS.Destination(
        identity,
        RNS.Destination.OUT,
        RNS.Destination.SINGLE,
        "lxmf",
        "delivery"
    )

    # Create a message with DIRECT method
    message = LXMF.LXMessage(
        destination=destination,
        source=_lxmf_destination,  # Our delivery destination as source
        content=content,
        title=title,
        fields=fields if fields else None,
        desired_method=LXMF.LXMessage.DIRECT
    )

    # Send via router
    _lxmf_router.handle_outbound(message)

    return {
        'sent': True,
        'status': 'queued',
        'message_hash': bytes_to_hex(message.hash) if message.hash else None
    }


def cmd_lxmf_send_opportunistic(params):
    """Send LXMF message via OPPORTUNISTIC delivery.

    params:
        destination_hash (hex): Destination hash (16 bytes)
        content (str): Message content
        title (str, optional): Message title
        fields (dict, optional): Additional fields

    Returns:
        sent (bool): True if message was queued
        message_hash (hex): Hash of the sent message
        method (str): "opportunistic"

    Note: This command requires that the destination has been announced
    and the identity is known to the transport layer.
    """
    global _lxmf_router, _lxmf_identity, _lxmf_destination

    if not _lxmf_router:
        return {
            'sent': False,
            'error': 'LXMF router not started'
        }

    RNS = _get_full_rns()
    import LXMF

    destination_hash = hex_to_bytes(params['destination_hash'])
    content = params['content']
    title = params.get('title', '')
    fields = params.get('fields', {})

    # Convert string field keys to int
    if fields:
        fields = {int(k): v for k, v in fields.items()}

    # Recall identity from destination hash
    identity = RNS.Identity.recall(destination_hash)
    if identity is None:
        return {
            'sent': False,
            'error': f'Cannot recall identity for {destination_hash.hex()}'
        }

    # Create outbound destination
    destination = RNS.Destination(
        identity,
        RNS.Destination.OUT,
        RNS.Destination.SINGLE,
        "lxmf",
        "delivery"
    )

    # Create LXMF message with OPPORTUNISTIC method
    message = LXMF.LXMessage(
        destination=destination,
        source=_lxmf_destination,
        content=content,
        title=title,
        fields=fields if fields else None,
        desired_method=LXMF.LXMessage.OPPORTUNISTIC
    )

    # Handle outbound
    _lxmf_router.handle_outbound(message)

    return {
        'sent': True,
        'message_hash': bytes_to_hex(message.hash) if message.hash else None,
        'method': 'opportunistic'
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
    'hdlc_escape': cmd_hdlc_escape,
    'hdlc_frame': cmd_hdlc_frame,
    'kiss_escape': cmd_kiss_escape,
    'kiss_frame': cmd_kiss_frame,
    # Link operations
    'link_id_from_packet': cmd_link_id_from_packet,
    'link_derive_key': cmd_link_derive_key,
    'link_encrypt': cmd_link_encrypt,
    'link_decrypt': cmd_link_decrypt,
    'link_prove': cmd_link_prove,
    'link_verify_proof': cmd_link_verify_proof,
    'link_signalling_bytes': cmd_link_signalling_bytes,
    'link_parse_signalling': cmd_link_parse_signalling,
    'link_rtt_pack': cmd_link_rtt_pack,
    'link_rtt_unpack': cmd_link_rtt_unpack,
    'link_request_pack': cmd_link_request_pack,
    'link_request_unpack': cmd_link_request_unpack,
    'link_response_pack': cmd_link_response_pack,
    'link_response_unpack': cmd_link_response_unpack,
    # Ratchet operations
    'ratchet_id': cmd_ratchet_id,
    'ratchet_public_from_private': cmd_ratchet_public_from_private,
    'ratchet_derive_key': cmd_ratchet_derive_key,
    'ratchet_encrypt': cmd_ratchet_encrypt,
    'ratchet_decrypt': cmd_ratchet_decrypt,
    'ratchet_storage_format': cmd_ratchet_storage_format,
    'ratchet_extract_from_announce': cmd_ratchet_extract_from_announce,
    # Announce operations
    'random_hash': cmd_random_hash,
    'announce_pack': cmd_announce_pack,
    'announce_unpack': cmd_announce_unpack,
    'announce_sign': cmd_announce_sign,
    'announce_verify': cmd_announce_verify,
    # Channel operations
    'envelope_pack': cmd_envelope_pack,
    'envelope_unpack': cmd_envelope_unpack,
    'stream_msg_pack': cmd_stream_msg_pack,
    'stream_msg_unpack': cmd_stream_msg_unpack,
    # Transport operations
    'path_entry_serialize': cmd_path_entry_serialize,
    'path_entry_deserialize': cmd_path_entry_deserialize,
    'path_request_pack': cmd_path_request_pack,
    'path_request_unpack': cmd_path_request_unpack,
    'packet_hashlist_pack': cmd_packet_hashlist_pack,
    'packet_hashlist_unpack': cmd_packet_hashlist_unpack,
    # Resource operations
    'resource_adv_pack': cmd_resource_adv_pack,
    'resource_adv_unpack': cmd_resource_adv_unpack,
    'resource_hash': cmd_resource_hash,
    'resource_flags': cmd_resource_flags,
    'hashmap_pack': cmd_hashmap_pack,
    'resource_map_hash': cmd_resource_map_hash,
    'resource_build_hashmap': cmd_resource_build_hashmap,
    'resource_proof': cmd_resource_proof,
    'resource_find_part': cmd_resource_find_part,
    # IFAC operations
    'ifac_derive_key': cmd_ifac_derive_key,
    'ifac_compute': cmd_ifac_compute,
    'ifac_verify': cmd_ifac_verify,
    # Compression operations
    'bz2_compress': cmd_bz2_compress,
    'bz2_decompress': cmd_bz2_decompress,
    # LXMF operations
    'lxmf_pack': cmd_lxmf_pack,
    'lxmf_unpack': cmd_lxmf_unpack,
    'lxmf_unpack_with_fields': cmd_lxmf_unpack_with_fields,
    'lxmf_hash': cmd_lxmf_hash,
    'lxmf_stamp_workblock': cmd_lxmf_stamp_workblock,
    'lxmf_stamp_valid': cmd_lxmf_stamp_valid,
    'lxmf_stamp_generate': cmd_lxmf_stamp_generate,
    # Live Reticulum/LXMF networking
    'rns_start': cmd_rns_start,
    'rns_stop': cmd_rns_stop,
    'lxmf_start_router': cmd_lxmf_start_router,
    'lxmf_get_messages': cmd_lxmf_get_messages,
    'lxmf_clear_messages': cmd_lxmf_clear_messages,
    'lxmf_announce': cmd_lxmf_announce,
    'lxmf_send_direct': cmd_lxmf_send_direct,
    'lxmf_send_opportunistic': cmd_lxmf_send_opportunistic,
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
