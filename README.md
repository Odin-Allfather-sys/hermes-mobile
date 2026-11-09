# Hermes Mobile

Native Android client for a Hermes Agent gateway. Chat with streaming, session
list, command approvals and clarify answers — the same JSON-RPC-over-WebSocket
wire protocol the Hermes desktop app speaks, against a remote gateway
(https + one-use 30s WS tickets minted from an authenticated login).

Kotlin, no Electron. Built with the CLI toolchain (Gradle 8.9, Android SDK 34).
Releases carry signed APKs.
