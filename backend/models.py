from pydantic import BaseModel
from typing import Optional

class RegisterRequest(BaseModel):
    client_id: str
    password: str
    kyber_public_key: str  # Base64 encoded
    dilithium_public_key: str  # Base64 encoded

class RegisterResponse(BaseModel):
    ciphertext: str  # Base64 encoded Kyber encapsulation

class Message(BaseModel):
    sender_id: str
    recipient_id: str
    content: str  # Base64 encoded encrypted content (text OR base64 image, both encrypted the same way)
    signature: str  # Base64 encoded Dilithium signature
    nonce: str # Base64 encoded AES nonce
    kem_ciphertext: str # Base64 encoded Kyber ciphertext for the recipient
    type: Optional[str] = "TEXT"
    # Unique id for this message, generated on the SENDER's device (UUID).
    # This id is what ties together the sent -> delivered -> seen status updates.
    # Optional + defaults None so nothing breaks if an old client omits it;
    # server falls back to generating one itself in that case.
    msg_id: Optional[str] = None

# NEW: request body for POST /profile -- updates a client's display name
# and/or profile picture. Both fields optional so the client can update
# just the name without re-sending the picture, or vice versa.
class ProfileRequest(BaseModel):
    client_id: str
    name: Optional[str] = None
    picture: Optional[str] = None  # Base64 encoded JPEG, optional

# NEW: response body for GET /profile/{client_id}. "name" always has a
# value (falls back to client_id if never set); "picture" is null if the
# user never uploaded one, so clients know to fall back to a letter avatar.
class ProfileResponse(BaseModel):
    client_id: str
    name: str
    picture: Optional[str] = None
    # NEW: last-seen timestamp in epoch milliseconds. Null when the user is
    # currently online, or when they have never gone offline since the
    # server started recording this field.
    last_seen: Optional[int] = None

# NEW: request body for POST /change-password -- lets a logged-in client
# change their own password. "current_password" is verified against the
# stored hash before "new_password" is accepted.
class ChangePasswordRequest(BaseModel):
    client_id: str
    current_password: str
    new_password: str