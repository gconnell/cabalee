# Cabalī

See https://cabalee.gram.co.nl for more documentation.

Cabalī utilizes connections with a mesh of nearby peers to provide local-area chat that doesn't traverse the internet.  No central services receive messages, only the phones near you.

Cabalī allows you to create small, ephemeral local chat sessions, with simple encryption to keep your chats secure-ish while allowing other phones to forward your messages on to their intended recipients.

Chat rooms are created utilizing preshared secret keys, with messages traversing the network using NaCL secret-box.  Symmetric keys are generated on the fly and sharable via QR codes.  Anyone that receives this code will have full future and (if they were listening prior, historic) access to all messages for that chat session.

Chat sessions are entirely ephemeral - no data hits persistent storage.  No chat messages, no chat session keys, nothing.  Turn off the app or restart the phone, and all information you carry will be wiped.

Local-area phone-to-phone communication takes place over Google's Nearby Connections API in P2P_CLUSTER mode, which does zero phone->server communication and relies mostly on local bluetooth (and possibly wifi-direct/wifi-aware) connections to pass data between devices.
