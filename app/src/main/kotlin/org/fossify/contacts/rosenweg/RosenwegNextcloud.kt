package org.fossify.contacts.rosenweg

import android.accounts.AbstractAccountAuthenticator
import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.accounts.AccountManager
import android.app.Activity
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.provider.ContactsContract
import android.util.Log
import androidx.work.WorkerParameters
import ch.rosenweg4303.cloud.Konto
import ch.rosenweg4303.cloud.KontoSpeicher
import ch.rosenweg4303.cloud.dav.DavArbeit
import ch.rosenweg4303.cloud.dav.DavPlaner
import ch.rosenweg4303.cloud.dav.KontakteAbgleich
import org.fossify.commons.extensions.hasPermission
import org.fossify.commons.helpers.PERMISSION_READ_CONTACTS
import org.fossify.commons.helpers.PERMISSION_WRITE_CONTACTS
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.contacts.extensions.config

/**
 * Rosenweg (Stefan 26.11.2026): die Adressbuecher der Rosenweg-Nextcloud direkt per CardDAV, ohne
 * DAVx5. Zugang: KontoSpeicher aus commons-rosenweg - auf geteilten Telefonen von Rosenweg Geraet,
 * auf dem eigenen Handy nach dem Anmeldeschirm (Rosenweg-Karte oder Rosenweg-Konto) ueber die
 * Anlage. Der Abgleich (commons: KontakteAbgleich) legt die Kontakte im Android-Adressbuch unter
 * dem Konto "Rosenweg-Nextcloud" ab; diese App zeigt und bearbeitet sie wie alle Kontakte.
 *
 * Wann: im Hintergrund alle 15 Minuten, kurz nach jeder Aenderung (WorkManager, [KontakteArbeit])
 * und beim Oeffnen (hoechstens alle 2 Minuten).
 *
 * Nur Nextcloud (Stefan 27.11.2026): neue Kontakte gehen in die Nextcloud (erstes beschreibbares
 * Adressbuch), nicht ins lokale Telefon-Adressbuch; zur Wahl beim Speichern steht nur noch die
 * Nextcloud. Andere Konten - z.B. das Rosenweg-Verzeichnis der geteilten Telefone - bleiben
 * sichtbar und unberuehrt.
 */
object RosenwegNextcloud {
    private const val TAG = "RosenwegNextcloud"
    /** Kontotyp, fuer den diese App den Authenticator stellt (res/xml/rosenweg_nextcloud_konto.xml). */
    const val KONTOTYP = "ch.rosenweg4303.contacts.nextcloud"
    private const val ABSTAND_MS = 2 * 60 * 1000L
    const val APP = "Rosenweg Kontakte (Android)"
    const val BESCHREIBUNG = "Deine Adressbücher aus der Rosenweg-Nextcloud erscheinen hier. Was du hier " +
        "einträgst oder änderst, geht sofort zurück in die Nextcloud – mit Fotos, Gruppen und Geburtstagen."

    val PLAN = DavPlaner.Plan("kontakte", KontakteArbeit::class.java, listOf(ContactsContract.AUTHORITY_URI))

    @Volatile private var zuletzt = 0L

    private fun berechtigt(c: Context) =
        c.hasPermission(PERMISSION_READ_CONTACTS) && c.hasPermission(PERMISSION_WRITE_CONTACTS)

    /** Gibt es das Nextcloud-Konto? Dann nur noch Nextcloud als Ziel fuer neue Kontakte. */
    fun aktiv(c: Context) = AccountManager.get(c).getAccountsByType(KONTOTYP).isNotEmpty()

    /** Quelle (Kontoname) fuer neue Kontakte, wenn die Nextcloud da ist; sonst null. */
    fun standardQuelle(c: Context): String? = if (aktiv(c)) KontakteAbgleich.KONTO_NAME else null

    /** Beim Oeffnen: Hintergrund planen und (hoechstens alle 2 Minuten) gleich abgleichen; [fertig] im UI-Thread. */
    fun abgleichen(a: Activity, sofort: Boolean = false, fertig: (Boolean) -> Unit = {}) {
        if (!berechtigt(a)) return
        DavPlaner.einrichten(a, PLAN)
        if (!sofort && System.currentTimeMillis() - zuletzt < ABSTAND_MS) return
        zuletzt = System.currentTimeMillis()
        val app = a.applicationContext
        ensureBackgroundThread {
            var geaendert = false
            try {
                geaendert = DavArbeit.exklusiv(PLAN.name) { abgleichenJetzt(app, KontoSpeicher(app).laden()) }
            } catch (e: Exception) {
                Log.w(TAG, "Abgleich: ${e.javaClass.simpleName} ${e.message}")
            }
            a.runOnUiThread { fertig(geaendert) }
        }
    }

    /** Anmeldung am geteilten Telefon u.ae.: im Hintergrund gleich abgleichen. */
    fun anstossen(c: Context) {
        if (!berechtigt(c)) return
        DavPlaner.einrichten(c, PLAN)
        DavPlaner.sofort(c, PLAN)
    }

    /** Blockierend (Hintergrund). true = etwas geaendert. */
    fun abgleichenJetzt(c: Context, konto: Konto?): Boolean {
        if (konto == null) {
            val da = aktiv(c)
            if (da) {
                KontakteAbgleich.entfernen(c, KONTOTYP)
                DavPlaner.abschalten(c, PLAN)
            }
            return da
        }
        val e = KontakteAbgleich(c, konto, APP, KONTOTYP).abgleichen()
        // neue Kontakte in die Nextcloud statt ins Telefon-Adressbuch
        if (c.config.lastUsedContactSource != KontakteAbgleich.KONTO_NAME) {
            c.config.lastUsedContactSource = KontakteAbgleich.KONTO_NAME
        }
        return e.hoch + e.herunter + e.geloescht > 0
    }

    fun wiederherstellen(c: Context, e: ch.rosenweg4303.cloud.dav.AbgleichProtokoll.Eintrag): Boolean {
        val konto = KontoSpeicher(c).laden() ?: return false
        return DavArbeit.exklusiv(PLAN.name) { KontakteAbgleich(c, konto, APP, KONTOTYP).wiederherstellen(e) }
    }
}

/** Hintergrund-Abgleich (WorkManager, siehe commons DavArbeit). */
class KontakteArbeit(c: Context, p: WorkerParameters) : DavArbeit(c, p) {
    override val plan get() = RosenwegNextcloud.PLAN
    override fun berechtigt() = applicationContext.hasPermission(PERMISSION_READ_CONTACTS) &&
        applicationContext.hasPermission(PERMISSION_WRITE_CONTACTS)
    override fun lokalGeaendert() = KontakteAbgleich.lokalGeaendert(applicationContext, RosenwegNextcloud.KONTOTYP)
    override fun abgleichen(konto: Konto) = RosenwegNextcloud.abgleichenJetzt(applicationContext, konto)
    // Im Hintergrund nie lokal loeschen: "kein Konto" kann auch heissen, dass Rosenweg Geraet gerade
    // nicht antwortet. Aufgeraeumt wird beim naechsten Oeffnen der App.
    override fun abgemeldet() { DavPlaner.abschalten(applicationContext, plan) }
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
