from motor.motor_asyncio import AsyncIOMotorClient

MONGO_URL = "mongodb://localhost:27017"
mongo_client = AsyncIOMotorClient(MONGO_URL) 

db = mongo_client["quantum_chat"]
clients_collection = db["clients"]
messages_collection = db["messages"]  # stores E2EE message payloads
pending_deletes_collection = db["pending_deletes"]  # delete-for-everyone events waiting for an offline recipient