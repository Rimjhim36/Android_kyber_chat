from fastapi import FastAPI, WebSocket, WebSocketDisconnect, HTTPException
from typing import Dict, Optional
import pydantic
import hashlib
import json
import base64
import uuid
from datetime import datetime, timezone

from models import RegisterRequest, RegisterResponse, Message, ProfileRequest, ProfileResponse, ChangePasswordRequest
from crypto_utils import generate_kyber_encapsulation, verify_dilithium_signature, derive_aes_key
from database import clients_collection, messages_collection, pending_deletes_collection

app = FastAPI()


class ConnectionManager:
    def __init__(self):
        self.active_connections: Dict[str, WebSocket] = {}

    async def connect(self, websocket: WebSocket, client_id: str):
        await websocket.accept()
        currently_online = list(self.active_connections.keys())
        self.active_connections[client_id] = websocket
        print(f"Client {client_id} connected.")

        try:
            await websocket.send_json({
                "type": "presence_list",
                "online_clients": currently_online
            })
        except Exception as e:
            print(f"Error sending presence_list to {client_id}: {e}")

        await self.broadcast_presence(client_id, "online", exclude=client_id)
        await self.deliver_pending_messages(client_id)
        await self.deliver_pending_deletes(client_id)  # NEW: delivers any delete-for-everyone events that missed this client while offline

    async def disconnect(self, websocket: WebSocket, client_id: str):
        if self.active_connections.get(client_id) is websocket:
            del self.active_connections[client_id]
            print(f"Client {client_id} disconnected.")

            # NEW: record the timestamp (epoch millis) at which this client went
            # offline, so it can be shown to other users as "Last seen at ...".
            last_seen_ms = int(datetime.now(timezone.utc).timestamp() * 1000)
            await clients_collection.update_one(
                {"_id": client_id},
                {"$set": {"last_seen": last_seen_ms}}
            )

            await self.broadcast_presence(client_id, "offline", exclude=client_id)
        else:
            print(f"Stale disconnect ignored for {client_id} (already replaced by a newer connection).")

    async def broadcast_presence(self, client_id: str, status: str, exclude: Optional[str] = None):
        payload = {
            "type": "presence",
            "client_id": client_id,
            "status": status
        }
        for cid, ws in list(self.active_connections.items()):
            if cid == exclude:
                continue
            try:
                await ws.send_json(payload)
            except Exception as e:
                print(f"Error broadcasting presence to {cid}: {e}")

    async def send_personal_message(self, message: dict, client_id: str):
        if client_id in self.active_connections:
            ws = self.active_connections[client_id]
            try:
                print(f"Sending to {client_id} via WebSocket...")
                await ws.send_json(message)
                print(f"Sent to {client_id}.")
            except Exception as e:
                print(f"Error sending to {client_id}: {e}")
                await self.disconnect(ws, client_id)
        else:
            print(f"Error: {client_id} is not connected.")

    async def send_status_update(self, target_client_id: str, msg_id: str, status: str):
        payload = {
            "type": "status_update",
            "msg_id": msg_id,
            "status": status  # "sent" | "delivered" | "seen"
        }
        await self.send_personal_message(payload, target_client_id)

    async def register_client(self, client_id: str, aes_key: bytes, dilithium_pk: str, kyber_pk: str, password_hash: str):
        client_data = {
            "_id": client_id,
            "aes_key_hex": aes_key.hex(),
            "dilithium_pk": dilithium_pk,
            "kyber_pk": kyber_pk,
            "password_hash": password_hash
        }
        await clients_collection.update_one(
            {"_id": client_id},
            {"$set": client_data},
            upsert=True
        )

    async def get_client_key(self, client_id: str) -> Optional[dict]:
        client = await clients_collection.find_one({"_id": client_id})
        if client:
            return {
                "dilithium_pk": client["dilithium_pk"],
                "kyber_pk": client["kyber_pk"]
            }
        return None

    async def get_client_profile(self, client_id: str) -> Optional[dict]:
        client = await clients_collection.find_one({"_id": client_id})
        if client:
            return {
                "client_id": client_id,
                "name": client.get("profile_name") or client_id,
                "picture": client.get("profile_picture"),
                # NEW: last_seen is None if the client is currently online or has
                # never disconnected since this field started being recorded.
                "last_seen": client.get("last_seen")
            }
        return None

    async def update_client_profile(self, client_id: str, name: Optional[str], picture: Optional[str]):
        update_fields = {}
        if name is not None:
            update_fields["profile_name"] = name
        if picture is not None:
            update_fields["profile_picture"] = picture
        if update_fields:
            await clients_collection.update_one(
                {"_id": client_id},
                {"$set": update_fields}
            )

    async def get_all_clients(self, exclude_id: Optional[str] = None):
        query = {"_id": {"$ne": exclude_id}} if exclude_id else {}
        cursor = clients_collection.find(query, {"_id": 1, "profile_name": 1, "profile_picture": 1})
        clients = await cursor.to_list(length=1000)
        return [
            {
                "client_id": c["_id"],
                "online": self.is_online(c["_id"]),
                "name": c.get("profile_name") or c["_id"],
                "picture": c.get("profile_picture")
            }
            for c in clients
        ]

    def is_online(self, client_id: str) -> bool:
        return client_id in self.active_connections

    async def store_message(self, message_dict: dict, delivered: bool) -> str:
        msg_id = message_dict.get("msg_id") or str(uuid.uuid4())
        doc = {
            "_id": msg_id,
            "sender_id": message_dict["sender_id"],
            "recipient_id": message_dict["recipient_id"],
            "content": message_dict["content"],
            "nonce": message_dict["nonce"],
            "kem_ciphertext": message_dict["kem_ciphertext"],
            "signature": message_dict["signature"],
            "type": message_dict.get("type", "TEXT"),
            "created_at": datetime.now(timezone.utc).isoformat(),
            "delivered": delivered,
            "status": "delivered" if delivered else "sent",
            "seen": False,
            "deleted": False,  # NEW: true once the sender deletes this message for everyone
        }
        await messages_collection.insert_one(doc)
        return msg_id

    async def deliver_pending_messages(self, client_id: str):
        cursor = messages_collection.find({"recipient_id": client_id, "delivered": False})
        pending = await cursor.to_list(length=1000)
        if not pending:
            return
        for doc in pending:
            payload = {
                "sender_id": doc["sender_id"],
                "recipient_id": doc["recipient_id"],
                "content": doc["content"],
                "nonce": doc["nonce"],
                "kem_ciphertext": doc["kem_ciphertext"],
                "signature": doc["signature"],
                "type": doc.get("type", "TEXT"),
                "msg_id": doc["_id"],
            }
            await self.send_personal_message(payload, client_id)
            await messages_collection.update_one(
                {"_id": doc["_id"]},
                {"$set": {"delivered": True, "status": "delivered"}}
            )
            await self.send_status_update(doc["sender_id"], doc["_id"], "delivered")

    # NEW: persists a delete-for-everyone event for a recipient who is currently offline,
    # so it can be replayed to them the next time they connect.
    async def store_pending_delete(self, payload: dict):
        await pending_deletes_collection.insert_one({
            "_id": str(uuid.uuid4()),
            "recipient_id": payload["recipient_id"],
            "msg_id": payload["msg_id"],
            "sender_id": payload["sender_id"],
        })

    # NEW: sends any queued delete-for-everyone events to a client that just connected.
    async def deliver_pending_deletes(self, client_id: str):
        cursor = pending_deletes_collection.find({"recipient_id": client_id})
        pending = await cursor.to_list(length=1000)
        if not pending:
            return
        for doc in pending:
            payload = {
                "type": "delete_for_everyone",
                "msg_id": doc["msg_id"],
                "sender_id": doc["sender_id"],
                "recipient_id": doc["recipient_id"],
            }
            await self.send_personal_message(payload, client_id)
            await pending_deletes_collection.delete_one({"_id": doc["_id"]})


manager = ConnectionManager()


def hash_password(password: str) -> str:
    return hashlib.sha256(password.encode("utf-8")).hexdigest()


# CHANGED: minimum password length raised from 5 to 8. The password is no
# longer restricted to digits only -- letters, digits and special
# characters are all allowed, in any combination.
MIN_PASSWORD_LENGTH = 8


def is_valid_password(password: str) -> bool:
    return len(password) >= MIN_PASSWORD_LENGTH


@app.post("/register", response_model=RegisterResponse)
async def register(request: RegisterRequest):
    if not is_valid_password(request.password):
        raise HTTPException(status_code=400, detail=f"Password must be at least {MIN_PASSWORD_LENGTH} characters")

    incoming_hash = hash_password(request.password)

    existing_client = await clients_collection.find_one({"_id": request.client_id})
    if existing_client:
        stored_hash = existing_client.get("password_hash")
        if stored_hash != incoming_hash:
            print(f"Login failed for {request.client_id}: Incorrect password")
            raise HTTPException(status_code=401, detail="Incorrect password")
        print(f"Login success for {request.client_id}")

    try:
        ciphertext_b64, shared_secret = generate_kyber_encapsulation(request.kyber_public_key)
    except Exception as e:
        raise HTTPException(status_code=400, detail=f"Kyber encapsulation failed: {str(e)}")

    aes_key = derive_aes_key(shared_secret)

    await manager.register_client(
        request.client_id, aes_key, request.dilithium_public_key, request.kyber_public_key, incoming_hash
    )

    return RegisterResponse(ciphertext=ciphertext_b64)


# NEW: lets a logged-in client change their own password. The current
# password is checked against the stored hash first, then the new
# password is validated and its hash replaces the old one.
@app.post("/change-password")
async def change_password(request: ChangePasswordRequest):
    existing_client = await clients_collection.find_one({"_id": request.client_id})
    if not existing_client:
        raise HTTPException(status_code=404, detail="Client not found")

    stored_hash = existing_client.get("password_hash")
    if hash_password(request.current_password) != stored_hash:
        raise HTTPException(status_code=401, detail="Current password is incorrect")

    if not is_valid_password(request.new_password):
        raise HTTPException(status_code=400, detail=f"New password must be at least {MIN_PASSWORD_LENGTH} characters")

    new_hash = hash_password(request.new_password)
    await clients_collection.update_one(
        {"_id": request.client_id},
        {"$set": {"password_hash": new_hash}}
    )
    return {"status": "ok"}


@app.post("/profile")
async def update_profile(request: ProfileRequest):
    existing_client = await clients_collection.find_one({"_id": request.client_id})
    if not existing_client:
        raise HTTPException(status_code=404, detail="Client not found")
    await manager.update_client_profile(request.client_id, request.name, request.picture)
    return {"status": "ok"}


@app.get("/profile/{client_id}", response_model=ProfileResponse)
async def get_profile(client_id: str):
    profile = await manager.get_client_profile(client_id)
    if profile:
        return profile
    raise HTTPException(status_code=404, detail="Client not found")


@app.websocket("/ws/{client_id}")
async def websocket_endpoint(websocket: WebSocket, client_id: str):
    await manager.connect(websocket, client_id)
    try:
        while True:
            data = await websocket.receive_text()
            print(f"DEBUG: Received raw data: {data}")
            message_dict = json.loads(data)

            if message_dict.get("type") == "seen":
                target_msg_id = message_dict.get("msg_id")
                original_sender_id = message_dict.get("sender_id")
                if target_msg_id and original_sender_id:
                    await messages_collection.update_one(
                        {"_id": target_msg_id},
                        {"$set": {"status": "seen", "seen": True}}
                    )
                    await manager.send_status_update(original_sender_id, target_msg_id, "seen")
                    print(f"Message {target_msg_id} marked SEEN, notified {original_sender_id}")
                continue

            # NEW: "delete_for_everyone" control frame, sent by the SENDER when they
            # delete their own message for everyone. Marks it deleted in the DB and
            # relays the event to the recipient (live, or queued if they're offline).
            if message_dict.get("type") == "delete_for_everyone":
                target_msg_id = message_dict.get("msg_id")
                del_sender_id = message_dict.get("sender_id")
                del_recipient_id = message_dict.get("recipient_id")
                if target_msg_id and del_sender_id and del_recipient_id:
                    await messages_collection.update_one(
                        {"_id": target_msg_id},
                        {"$set": {"deleted": True}}
                    )
                    delete_payload = {
                        "type": "delete_for_everyone",
                        "msg_id": target_msg_id,
                        "sender_id": del_sender_id,
                        "recipient_id": del_recipient_id
                    }
                    if manager.is_online(del_recipient_id):
                        await manager.send_personal_message(delete_payload, del_recipient_id)
                    else:
                        await manager.store_pending_delete(delete_payload)
                    print(f"Delete-for-everyone: {target_msg_id} by {del_sender_id}")
                continue

            try:
                msg = Message(**message_dict)
            except Exception as e:
                print(f"ERROR: Malformed message: {e}")
                continue

            sender_keys = await manager.get_client_key(msg.sender_id)
            if not sender_keys:
                print(f"Sender {msg.sender_id} not found")
                continue

            sender_pk = sender_keys["dilithium_pk"]

            try:
                sender_id_bytes = msg.sender_id.encode('utf-8')
                recipient_id_bytes = msg.recipient_id.encode('utf-8')
                content_b64_bytes = msg.content.encode('utf-8')
                nonce_b64_bytes = msg.nonce.encode('utf-8')
                kem_ciphertext_b64_bytes = msg.kem_ciphertext.encode('utf-8')

                payload = sender_id_bytes + recipient_id_bytes + content_b64_bytes + nonce_b64_bytes + kem_ciphertext_b64_bytes
                payload_hash = hashlib.sha256(payload).digest()
            except Exception as e:
                print(f"Error constructing payload for verification: {e}")
                continue

            is_valid = verify_dilithium_signature(sender_pk, payload, msg.signature)

            if is_valid:
                print(f"Signature Verification: SUCCESS")
            else:
                print(f"Signature Verification: FAILED -- message rejected")
                continue

            msg_id = msg.msg_id or str(uuid.uuid4())
            message_dict["msg_id"] = msg_id

            recipient_online = manager.is_online(msg.recipient_id)
            await manager.store_message(message_dict, delivered=recipient_online)

            await manager.send_status_update(msg.sender_id, msg_id, "sent")

            if recipient_online:
                print(f"Forwarding message from {msg.sender_id} to {msg.recipient_id}")
                await manager.send_personal_message(message_dict, msg.recipient_id)
                await manager.send_status_update(msg.sender_id, msg_id, "delivered")
            else:
                print(f"{msg.recipient_id} is offline -- message stored for later delivery")

    except WebSocketDisconnect:
        await manager.disconnect(websocket, client_id)


@app.get("/key/{client_id}")
async def get_key(client_id: str):
    keys = await manager.get_client_key(client_id)
    if keys:
        return keys
    raise HTTPException(status_code=404, detail="Client not found")


@app.get("/status/{client_id}")
async def get_status(client_id: str):
    return {"client_id": client_id, "online": manager.is_online(client_id)}


@app.get("/contacts")
async def get_contacts(exclude: Optional[str] = None):
    contacts = await manager.get_all_clients(exclude_id=exclude)
    return {"contacts": contacts}


@app.get("/history/{user_a}/{user_b}")
async def get_history(user_a: str, user_b: str, limit: int = 200):
    cursor = messages_collection.find({
        "$or": [
            {"sender_id": user_a, "recipient_id": user_b},
            {"sender_id": user_b, "recipient_id": user_a},
        ]
    }).sort("created_at", 1).limit(limit)
    history = await cursor.to_list(length=limit)
    for h in history:
        h["_id"] = str(h["_id"])
    return {"messages": history}


class LogRequest(pydantic.BaseModel):
    message: str


@app.post("/log")
async def log_message(request: LogRequest):
    print(f"CLIENT LOG: {request.message}")
    return {"status": "ok"}