package org.fossify.contacts.rosenweg

import android.app.Activity
import android.app.Service
import android.accounts.AbstractAccountAuthenticator
import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import ch.rosenweg4303.cloud.KontoSpeicher
import ch.rosenweg4303.cloud.dav.KontakteAbgleich
import org.fossify.commons.extensions.hasPermission
import org.fossify.commons.helpers.PERMISSION_READ_CONTACTS
import org.fossify.commons.helpers.PERMISSION_WRITE_CONTACTS
import org.fossify.commons.helpers.ensureBackgroundThread

/**
 * Rosenweg (Stefan 26.11.2026): die Adressbuecher der Rosenweg-Nextcloud direkt per CardDAV, ohne
 * DAVx5. Zugang: KontoSpeicher aus commons-rosenweg - auf geteilten Telefonen von Rosenweg Geraet,
 * auf dem eigenen Handy nach dem Anmeldeschirm (Rosenweg-Karte oder Rosenweg-Konto) ueber die
 * Anlage. Der Abgleich (commons: KontakteAbgleich) legt die Kontakte im Android-Adressbuch unter
 * dem Konto "Rosenweg-Nextcloud" ab; diese App zeigt und bearbeitet sie wie alle Kontakte.
 * Aenderungen gehen beim naechsten Abgleich hoch (beim Oeffnen, hoechstens alle 10 Minuten).
 */
object RosenwegNextcloud {
    private const val TAG = "RosenwegNextcloud"
    /** Kontotyp, fuer den diese App den Authenticator stellt (res/xml/rosenweg_nextcloud_konto.xml). */
    const val KONTOTYP = "ch.rosenweg4303.contacts.nextcloud"
    private const val ABSTAND_MS = 10 * 60 * 1000L
    private const val APP = "Rosenweg Kontakte (Android)"
    const val BESCHREIBUNG = "Deine Adressbücher aus der Rosenweg-Nextcloud erscheinen hier. Was du hier " +
        "einträgst oder änderst, geht zurück in die Nextcloud."

    @Volatile private var zuletzt = 0L
    @Volatile private var laeuft = false

    /** Im Hintergrund abgleichen; [fertig] im UI-Thread (true = etwas geaendert, neu anzeigen). */
    fun abgleichen(a: Activity, sofort: Boolean = false, fertig: (Boolean) -> Unit = {}) {
        if (laeuft || (!sofort && System.currentTimeMillis() - zuletzt < ABSTAND_MS)) return
        if (!a.hasPermission(PERMISSION_READ_CONTACTS) || !a.hasPermission(PERMISSION_WRITE_CONTACTS)) return
        laeuft = true
        zuletzt = System.currentTimeMillis()
        ensureBackgroundThread {
            var geaendert = false
            try {
                val konto = KontoSpeicher(a).laden()
                if (konto == null) {
                    val da = android.accounts.AccountManager.get(a).getAccountsByType(KONTOTYP).isNotEmpty()
                    if (da) KontakteAbgleich.entfernen(a, KONTOTYP)
                    geaendert = da
                } else {
                    val e = KontakteAbgleich(a, konto, APP, KONTOTYP).abgleichen()
                    geaendert = e.hoch + e.herunter + e.geloescht > 0
                }
            } catch (e: Exception) {
                Log.w(TAG, "Abgleich: ${e.javaClass.simpleName} ${e.message}")
            } finally {
                laeuft = false
            }
            a.runOnUiThread { fertig(geaendert) }
        }
    }
}

/** Authenticator fuer das Konto "Rosenweg-Nextcloud" (nur lokal; anmelden geht ueber die App). */
class NextcloudKontoDienst : Service() {
    private val authenticator by lazy {
        object : AbstractAccountAuthenticator(this) {
            override fun editProperties(r: AccountAuthenticatorResponse?, t: String?): Bundle? = null
            override fun addAccount(r: AccountAuthenticatorResponse?, t: String?, a: String?, f: Array<out String>?, o: Bundle?): Bundle? = null
            override fun confirmCredentials(r: AccountAuthenticatorResponse?, a: Account?, o: Bundle?): Bundle? = null
            override fun getAuthToken(r: AccountAuthenticatorResponse?, a: Account?, t: String?, o: Bundle?): Bundle? = null
            override fun getAuthTokenLabel(t: String?): String? = null
            override fun updateCredentials(r: AccountAuthenticatorResponse?, a: Account?, t: String?, o: Bundle?): Bundle? = null
            override fun hasFeatures(r: AccountAuthenticatorResponse?, a: Account?, f: Array<out String>?): Bundle? = null
        }
    }

    override fun onBind(intent: Intent?): IBinder? = authenticator.iBinder
}
