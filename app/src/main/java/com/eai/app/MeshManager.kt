package com.eai.app

import android.content.Context
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import org.json.JSONObject
import java.util.UUID

data class Msg(
    val id: String,
    val nick: String,
    val avatar: String,
    val text: String,
    val time: Long,
    val saved: Boolean = false
)

class MeshManager(
    context: Context,
    private val nick: String,
    private val avatar: String,
    private val onMessage: (Msg) -> Unit,
    private val onPeers: (Int) -> Unit
) {
    private val client = Nearby.getConnectionsClient(context)
    private val serviceId = "com.eai.app"
    private val peers = mutableSetOf<String>()
    private val seen = mutableSetOf<String>()
    private val myName = nick + "#" + UUID.randomUUID().toString().take(4)

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return
            try {
                val o = JSONObject(String(bytes, Charsets.UTF_8))
                val id = o.getString("id")
                if (!seen.add(id)) return
                onMessage(
                    Msg(id, o.getString("n"), o.getString("a"), o.getString("t"), o.getLong("ts"))
                )
                relay(bytes, endpointId)
            } catch (e: Exception) {
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    private val connectionCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            client.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                peers.add(endpointId)
                onPeers(peers.size)
            }
        }

        override fun onDisconnected(endpointId: String) {
            peers.remove(endpointId)
            onPeers(peers.size)
        }
    }

    private val discoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (endpointId in peers) return
            client.requestConnection(myName, endpointId, connectionCallback)
        }

        override fun onEndpointLost(endpointId: String) {}
    }

    fun start() {
        val strategy = Strategy.P2P_CLUSTER
        client.startAdvertising(
            myName, serviceId, connectionCallback,
            AdvertisingOptions.Builder().setStrategy(strategy).build()
        )
        client.startDiscovery(
            serviceId, discoveryCallback,
            DiscoveryOptions.Builder().setStrategy(strategy).build()
        )
    }

    fun stop() {
        client.stopAdvertising()
        client.stopDiscovery()
        client.stopAllEndpoints()
        peers.clear()
    }

    fun send(text: String): Msg {
        val msg = Msg(UUID.randomUUID().toString(), nick, avatar, text, System.currentTimeMillis())
        seen.add(msg.id)
        val o = JSONObject()
            .put("id", msg.id).put("n", msg.nick).put("a", msg.avatar)
            .put("t", msg.text).put("ts", msg.time)
        relay(o.toString().toByteArray(Charsets.UTF_8), null)
        return msg
    }

    private fun relay(bytes: ByteArray, except: String?) {
        val targets = peers.filter { it != except }
        if (targets.isNotEmpty()) client.sendPayload(targets, Payload.fromBytes(bytes))
    }
}