package dev.qualia.qualia2fa

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.qualia.qualia2fa.resources.Res
import dev.qualia.qualia2fa.resources.action_authorize_device
import dev.qualia.qualia2fa.resources.action_done
import dev.qualia.qualia2fa.resources.action_pair_new_device
import dev.qualia.qualia2fa.resources.action_scan_request
import dev.qualia.qualia2fa.resources.error_image_has_no_request
import dev.qualia.qualia2fa.resources.error_image_unreadable
import dev.qualia.qualia2fa.resources.field_joiner_request
import dev.qualia.qualia2fa.resources.lan_permission_refused
import dev.qualia.qualia2fa.resources.message_copied
import dev.qualia.qualia2fa.resources.message_request_read
import dev.qualia.qualia2fa.resources.pair_authorized
import dev.qualia.qualia2fa.resources.pair_authorized_with_requester
import dev.qualia.qualia2fa.resources.pair_code_title
import dev.qualia.qualia2fa.resources.pair_error_no_key_store
import dev.qualia.qualia2fa.resources.pair_error_request_rejected
import dev.qualia.qualia2fa.resources.pair_intro
import dev.qualia.qualia2fa.resources.pair_lan_code_changed
import dev.qualia.qualia2fa.resources.pair_lan_enable
import dev.qualia.qualia2fa.resources.pair_lan_explainer
import dev.qualia.qualia2fa.resources.pair_lan_listening
import dev.qualia.qualia2fa.resources.pair_lan_no_identity
import dev.qualia.qualia2fa.resources.pair_lan_reply_sent
import dev.qualia.qualia2fa.resources.pair_lan_request_from
import dev.qualia.qualia2fa.resources.pair_lan_send_failed
import dev.qualia.qualia2fa.resources.pair_lan_section
import dev.qualia.qualia2fa.resources.pair_lan_unavailable
import dev.qualia.qualia2fa.resources.pair_lan_window_over
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

/**
 * The side that already holds the account, in the sync screen's language.
 *
 * It shows a payload for the other device to read, takes that device's request, and hands
 * back a reply. Authorizing is the moment the master key is committed to the session, so the
 * requester's identity is shown next to it and the reply is only handed over when the user
 * asks for it: until the reply reaches the other device, nothing has been given away.
 *
 * # The local network is an addition, not a replacement
 *
 * With it switched on, the code also names this device on the network, and the request comes
 * in by itself instead of being carried by hand. Everything else is the same flow over the
 * same input field, and with the switch off the screen is the one it has always been: the
 * payload alone, a field, and a reply to hand back.
 */
@Composable
fun PairNewDevicePanel(
    vault: Vault,
    deviceId: String?,
) {
    val host = remember { Qualia.beginPairing() }
    var requestText by remember { mutableStateOf("") }
    var replyText by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var lanOn by remember { mutableStateOf(false) }
    var lanChannel by remember { mutableStateOf<LanChannel?>(null) }
    var lanLobby by remember { mutableStateOf<LanLobby?>(null) }
    var lanSender by remember { mutableStateOf<String?>(null) }
    var lanReplySent by remember { mutableStateOf(false) }
    var lanReplyBytes by remember { mutableStateOf<ByteArray?>(null) }
    var lanReplyPending by remember { mutableStateOf(false) }

    val copied = stringResource(Res.string.message_copied)
    val unreadableImage = stringResource(Res.string.error_image_unreadable)
    val noRequest = stringResource(Res.string.error_image_has_no_request)
    val requestRead = stringResource(Res.string.message_request_read)
    val requestRejected = stringResource(Res.string.pair_error_request_rejected)
    val lanUnavailable = stringResource(Res.string.pair_lan_unavailable)
    val lanNoIdentity = stringResource(Res.string.pair_lan_no_identity)
    val lanRefused = stringResource(Res.string.lan_permission_refused)
    val lanWindowOver = stringResource(Res.string.pair_lan_window_over)
    val lanSendFailed = stringResource(Res.string.pair_lan_send_failed)
    val lanCodeChanged = stringResource(Res.string.pair_lan_code_changed)
    val multicast = rememberMulticastHold()
    val permission = rememberLanPermission()

    if (host == null) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            MessageLine(stringResource(Res.string.pair_error_no_key_store))
        }
        return
    }

    val lanRecord = remember(lanChannel) { lanChannel?.record() }
    val code = if (lanRecord == null) host.payload else host.codeText(lanRecord)

    /** Opens an endpoint and its lobby, which is what being findable on the network costs. */
    fun openLan() {
        val id = deviceId ?: return
        // Before the sockets: see the note in the sync screen. The hold is what makes the
        // system deliver multicast datagrams to this application at all.
        multicast.acquire()
        val channel = Qualia.lanChannel(id)
        if (channel == null) {
            multicast.release()
            message = lanUnavailable
            return
        }
        val lobby = channel.openLobby()
        if (lobby == null) {
            multicast.release()
            channel.close()
            message = lanUnavailable
            return
        }
        lanChannel = channel
        lanLobby = lobby
        lobby.announceNow()
        lanOn = true
        message = lanCodeChanged
    }

    /** Closes the endpoint and its lobby, and gives the multicast hold back. */
    fun closeLan() {
        lanLobby?.close()
        lanChannel?.close()
        multicast.release()
        lanLobby = null
        lanChannel = null
        lanSender = null
        lanReplySent = false
        lanReplyBytes = null
        lanReplyPending = false
        lanOn = false
    }

    /** Tries the reply once, registering the requester if the lobby has heard it by now. */
    fun attemptReply(bytes: ByteArray): Boolean {
        val sender = lanSender ?: return false
        val channel = lanChannel ?: return false
        lanLobby?.rememberPeer(channel, sender)
        return channel.sendTo(sender, bytes)
    }

    /**
     * Hands the reply back over the network, when that is where the request came from.
     *
     * A send that does not go through is retried for a short while and then reported, because
     * the reply is otherwise only on screen: the other device is waiting for a message that
     * will never arrive, and the user has to know to carry the code below by hand. The reply
     * is shown either way.
     */
    fun replyOverLan(reply: String) {
        // Only a request that came over the network has an address to answer; a request typed
        // or scanned in is answered by the code below and never enters this route.
        if (lanSender == null || lanChannel == null) return
        val bytes = decodeFromText(reply) ?: return
        lanReplyBytes = bytes
        if (attemptReply(bytes)) {
            lanReplySent = true
            lanReplyPending = false
            message = null
        } else {
            // The request arrived on a connection, but a message still has to name a device
            // the channel has heard of. The joining device announces itself while it waits,
            // so a reply that is a fraction of a second early is worth trying again instead of
            // giving up: the code below is the fallback, not the first answer. The failure is
            // only reported after the retry window, so a send that succeeds in the next half
            // second does not flash an error first.
            lanReplySent = false
            lanReplyPending = true
            message = null
        }
    }

    LaunchedEffect(lanReplyPending, lanReplyBytes) {
        if (!lanReplyPending) return@LaunchedEffect
        val bytes = lanReplyBytes ?: return@LaunchedEffect
        val deadline = TimeSource.Monotonic.markNow() + LAN_REPLY_RETRY_WINDOW
        while (!deadline.hasPassedNow()) {
            if (attemptReply(bytes)) {
                lanReplySent = true
                lanReplyPending = false
                message = null
                return@LaunchedEffect
            }
            delay(LAN_REPLY_RETRY_MS)
        }
        lanReplyPending = false
        message = lanSendFailed
    }

    LaunchedEffect(lanChannel) {
        val channel = lanChannel ?: return@LaunchedEffect
        val lobby = lanLobby ?: return@LaunchedEffect
        val deadline = TimeSource.Monotonic.markNow() + LAN_REQUEST_WINDOW
        var arrived = false
        while (!arrived && !deadline.hasPassedNow() && requestText.isBlank() && replyText == null) {
            // The pairing code is carried by hand, but the request comes back over the network
            // only if the joining device can find this one. Repeating the announcement while
            // the window is open is what covers a lost multicast datagram: one announcement is
            // all the transport sends by itself for the next thirty seconds.
            lobby.announceNow()
            val taken = channel.receive(LAN_POLL_MS) ?: continue
            requestText = encodeToText(taken.message)
            lanSender = taken.sender
            lanReplySent = false
            message = null
            lanLobby?.rememberPeer(channel, taken.sender)
            arrived = true
        }

        if (!arrived) {
            closeLan()
            if (requestText.isBlank() && replyText == null) message = lanWindowOver
        }
    }

    DisposableEffect(Unit) {
        onDispose { closeLan() }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Space.l),
    ) {
        Hint(stringResource(Res.string.pair_intro))

        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            // The code and the request are read in that order, side by side when the window is
            // wide enough for both and stacked when it is not. That is the same shape the
            // design uses: the thing the other device scans on the left, what this device does
            // with the answer on the right.
            val twoColumns = maxWidth >= PAIR_TWO_COLUMN_WIDTH

            val codeCard: @Composable () -> Unit = {
                SectionCard(title = stringResource(Res.string.pair_code_title)) {
                    // Smaller than a full-width code, because this card shares the width with
                    // the request column whenever the window has two of them.
                    CodeBlock(code = code, onCopied = { message = copied }, qrSizeDp = 200)
                }
            }

            val lanCard: @Composable () -> Unit = {
                SectionCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Space.m),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(Res.string.pair_lan_section),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                stringResource(Res.string.pair_lan_enable),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = lanOn,
                            onCheckedChange = { wanted ->
                                if (!wanted) {
                                    closeLan()
                                    message = lanCodeChanged
                                } else if (deviceId == null) {
                                    message = lanNoIdentity
                                } else {
                                    permission.request { allowed ->
                                        if (allowed) openLan() else message = lanRefused
                                    }
                                }
                            },
                        )
                    }
                    Hint(stringResource(Res.string.pair_lan_explainer))
                    if (lanOn) {
                        Hint(stringResource(Res.string.pair_lan_listening))
                    }
                    lanSender?.let { sender ->
                        MessageLine(
                            stringResource(Res.string.pair_lan_request_from, shortDeviceId(sender)),
                            isError = false,
                        )
                    }
                    if (lanReplySent) {
                        MessageLine(stringResource(Res.string.pair_lan_reply_sent), isError = false)
                    }
                }
            }

            val requestCard: @Composable () -> Unit = {
                SectionCard(title = stringResource(Res.string.field_joiner_request)) {
                    CodeField(
                        value = requestText,
                        onValueChange = { requestText = it },
                        label = stringResource(Res.string.field_joiner_request),
                        scanLabel = stringResource(Res.string.action_scan_request),
                        unreadable = unreadableImage,
                        noCode = noRequest,
                        onScanned = { scanned ->
                            // The code carries this application's envelope; what belongs in the
                            // field is what was put into it.
                            requestText = Qualia.unwrapQrText(scanned) ?: scanned
                            message = requestRead
                        },
                        onMessage = { message = it },
                    )
                    Button(
                        onClick = {
                            val reply = host.replyTo(requestText.trim(), vault)
                            if (reply == null) {
                                message = requestRejected
                            } else {
                                replyText = reply
                                message = null
                                replyOverLan(reply)
                            }
                        },
                        enabled = requestText.isNotBlank() && replyText == null,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(Res.string.action_authorize_device))
                    }
                }
            }

            val replyCard: @Composable () -> Unit = {
                replyText?.let { reply ->
                    val requester = host.joinerDeviceId
                    SectionCard {
                        Text(
                            if (requester == null) {
                                stringResource(Res.string.pair_authorized)
                            } else {
                                stringResource(Res.string.pair_authorized_with_requester, requester)
                            },
                        )
                        CodeBlock(code = reply, onCopied = { message = copied })
                    }
                }
            }

            if (twoColumns) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Space.l),
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(Space.l),
                    ) {
                        codeCard()
                        replyCard()
                    }
                    Column(
                        modifier = Modifier.weight(1.2f),
                        verticalArrangement = Arrangement.spacedBy(Space.l),
                    ) {
                        lanCard()
                        requestCard()
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(Space.l)) {
                    codeCard()
                    lanCard()
                    requestCard()
                    replyCard()
                }
            }
        }

        MessageLine(message)
    }
}

/**
 * From this content width the pairing screen puts the code and the request side by side.
 *
 * The left column gets a little under half of it, which has to stay wide enough for the
 * 200 dp code plus the card's own padding.
 */
private val PAIR_TWO_COLUMN_WIDTH = 540.dp

/**
 * How long the switch waits for a request before it stops listening.
 *
 * The endpoint is opened by an explicit action and closes itself after this, so a screen left
 * open does not announce this device into the network indefinitely. A request that was typed
 * or read out of an image in the meantime belongs to the user's own route: the endpoint closes
 * all the same, but the message about the wait is left out rather than talking over them.
 */
private val LAN_REQUEST_WINDOW = 2.minutes

/** How long one attempt at taking a message waits, within [LAN_REQUEST_WINDOW]. */
private const val LAN_POLL_MS = 2_000L

/** How often a reply that could not be sent yet is tried again. */
private const val LAN_REPLY_RETRY_MS = 500L

/**
 * How long the reply is retried.
 *
 * Long enough for the joining device's next announcement to be heard and registered, short
 * enough that a user who is waiting on the other device is not left without an answer.
 */
private val LAN_REPLY_RETRY_WINDOW = 15.seconds
