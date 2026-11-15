package com.souly.parental.shield.services

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Telephony
import androidx.core.content.ContextCompat
import com.souly.parental.shield.data.prefs.SecureTokenStore
import com.souly.parental.shield.data.remote.SoulyApiClient
import com.souly.parental.shield.data.remote.dto.CallLogItemDto
import com.souly.parental.shield.data.remote.dto.ContactEntryDto
import com.souly.parental.shield.data.remote.dto.SmsLogItemDto
import com.souly.parental.shield.data.remote.dto.TelephonySyncRequest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TelephonyAndContactsSyncManager(private val context: Context) {

    private val tokenStore = SecureTokenStore(context.applicationContext)

    suspend fun syncTelephonyLogsAndContacts() {
        if (!tokenStore.isDevicePaired() || !tokenStore.isFeatureEnabled("telephony_contact_rules")) return

        val calls = readRecentCallLogs()
        val smsList = readRecentSmsMessages()
        val contacts = readDeviceContactsBook()

        if (calls.isEmpty() && smsList.isEmpty() && contacts.isEmpty()) return

        try {
            val api = SoulyApiClient.create(context)
            val res = api.syncTelephonyAndContacts(
                TelephonySyncRequest(
                    calls = calls,
                    sms_messages = smsList,
                    contacts = contacts
                )
            )
            res.data?.restricted_numbers?.let { blockedList ->
                tokenStore.saveRestrictedContacts(blockedList)
            }
        } catch (e: Exception) {
        }
    }

    private fun readRecentCallLogs(): List<CallLogItemDto> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }
        val list = mutableListOf<CallLogItemDto>()
        val lastWatermark = tokenStore.getLastSyncedCallTimestamp()
        var newestTimestamp = lastWatermark
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

        try {
            val cursor = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(
                    CallLog.Calls.CACHED_NAME,
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.TYPE,
                    CallLog.Calls.DURATION,
                    CallLog.Calls.DATE
                ),
                "${CallLog.Calls.DATE} > ?",
                arrayOf(lastWatermark.toString()),
                "${CallLog.Calls.DATE} DESC"
            )
            cursor?.use { c ->
                val nameIdx = c.getColumnIndex(CallLog.Calls.CACHED_NAME)
                val numIdx = c.getColumnIndex(CallLog.Calls.NUMBER)
                val typeIdx = c.getColumnIndex(CallLog.Calls.TYPE)
                val durIdx = c.getColumnIndex(CallLog.Calls.DURATION)
                val dateIdx = c.getColumnIndex(CallLog.Calls.DATE)

                var count = 0
                while (c.moveToNext() && count < 30) {
                    val number = if (numIdx >= 0) c.getString(numIdx)?.trim() ?: "" else ""
                    if (number.isBlank()) continue
                    val name = if (nameIdx >= 0) c.getString(nameIdx)?.trim().takeUnless { it.isNullOrBlank() } ?: number else number
                    val rawType = if (typeIdx >= 0) c.getInt(typeIdx) else CallLog.Calls.INCOMING_TYPE
                    val duration = if (durIdx >= 0) c.getInt(durIdx).coerceAtLeast(0) else 0
                    val dateMillis = if (dateIdx >= 0) c.getLong(dateIdx) else System.currentTimeMillis()

                    if (dateMillis > newestTimestamp) {
                        newestTimestamp = dateMillis
                    }

                    val callTypeStr = when (rawType) {
                        CallLog.Calls.OUTGOING_TYPE -> "outgoing"
                        CallLog.Calls.MISSED_TYPE -> "missed"
                        CallLog.Calls.REJECTED_TYPE, CallLog.Calls.BLOCKED_TYPE -> "rejected"
                        else -> "incoming"
                    }

                    list.add(
                        CallLogItemDto(
                            contact_name = name,
                            phone_number = number,
                            call_type = callTypeStr,
                            duration_seconds = duration,
                            sim_slot_index = 0,
                            called_at = dateFormat.format(Date(dateMillis))
                        )
                    )
                    count++
                }
            }
            if (newestTimestamp > lastWatermark) {
                tokenStore.saveLastSyncedCallTimestamp(newestTimestamp)
            }
        } catch (e: Exception) {
        }
        return list
    }

    private fun readRecentSmsMessages(): List<SmsLogItemDto> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }
        val list = mutableListOf<SmsLogItemDto>()
        val lastWatermark = tokenStore.getLastSyncedSmsTimestamp()
        var newestTimestamp = lastWatermark
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

        try {
            val cursor = context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(
                    Telephony.Sms.ADDRESS,
                    Telephony.Sms.BODY,
                    Telephony.Sms.TYPE,
                    Telephony.Sms.DATE
                ),
                "${Telephony.Sms.DATE} > ?",
                arrayOf(lastWatermark.toString()),
                "${Telephony.Sms.DATE} DESC"
            )
            cursor?.use { c ->
                val addrIdx = c.getColumnIndex(Telephony.Sms.ADDRESS)
                val bodyIdx = c.getColumnIndex(Telephony.Sms.BODY)
                val typeIdx = c.getColumnIndex(Telephony.Sms.TYPE)
                val dateIdx = c.getColumnIndex(Telephony.Sms.DATE)

                var count = 0
                while (c.moveToNext() && count < 30) {
                    val address = if (addrIdx >= 0) c.getString(addrIdx)?.trim() ?: "" else ""
                    val body = if (bodyIdx >= 0) c.getString(bodyIdx)?.trim() ?: "" else ""
                    if (address.isBlank() || body.isBlank() || body.startsWith("SOULY#")) continue
                    if (isVerificationCodeSms(body)) continue

                    val smsType = if (typeIdx >= 0) c.getInt(typeIdx) else Telephony.Sms.MESSAGE_TYPE_INBOX
                    val dateMillis = if (dateIdx >= 0) c.getLong(dateIdx) else System.currentTimeMillis()
                    if (dateMillis > newestTimestamp) {
                        newestTimestamp = dateMillis
                    }

                    list.add(
                        SmsLogItemDto(
                            sender_name = address,
                            phone_number = address,
                            direction = if (smsType == Telephony.Sms.MESSAGE_TYPE_SENT) "outgoing" else "incoming",
                            sms_body = body.take(400),
                            received_at = dateFormat.format(Date(dateMillis))
                        )
                    )
                    count++
                }
            }
            if (newestTimestamp > lastWatermark) {
                tokenStore.saveLastSyncedSmsTimestamp(newestTimestamp)
            }
        } catch (e: Exception) {
        }
        return list
    }

    private fun isVerificationCodeSms(body: String): Boolean {
        val lower = body.lowercase()
        return lower.contains("otp") ||
            lower.contains("verification code") ||
            lower.contains("رمز التحقق") ||
            lower.contains("كود التفعيل")
    }

    private fun readDeviceContactsBook(): List<ContactEntryDto> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }
        val list = mutableListOf<ContactEntryDto>()
        try {
            val cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null,
                null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )
            cursor?.use { c ->
                val nameIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                var count = 0
                while (c.moveToNext() && count < 80) {
                    val name = if (nameIdx >= 0) c.getString(nameIdx)?.trim() ?: "" else ""
                    val num = if (numIdx >= 0) c.getString(numIdx)?.trim() ?: "" else ""
                    if (name.isNotBlank() && num.isNotBlank()) {
                        list.add(ContactEntryDto(contact_name = name, phone_number = num))
                        count++
                    }
                }
            }
        } catch (e: Exception) {
        }
        return list.distinctBy { "${it.contact_name}|${it.phone_number}" }
    }
}
