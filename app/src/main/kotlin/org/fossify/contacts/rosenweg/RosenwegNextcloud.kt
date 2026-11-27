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
import org.fossify.commons.extensions.baseConfig
import org.fossify.commons.extensions.getAllContactSources
import org.fossify.commons.models.contacts.Group
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
@Suppress("TooManyFunctions")
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

    /**
     * Stefan 27.11.2026: Die App zeigt NUR Kontakte aus der Nextcloud. Alle anderen Quellen auf dem
     * Telefon (Telefon/lokal, Google, SIM, WhatsApp, Rosenweg-Verzeichnis von Geraet ...) kommen in
     * Fossifys "ignorierte Quellen" - damit sind sie weg aus Liste, Favoriten, Suche und Export.
     * Andere Apps (z.B. Rosenweg Telefon) sind davon nicht betroffen. Bei jedem Oeffnen und nach dem
     * Abgleich neu setzen (neue Konten kommen dazu). true = geaendert.
     */
    fun quellenFestlegen(c: Context): Boolean {
        val ignoriert = c.getAllContactSources().filter { it.type != KONTOTYP }.map { it.getFullIdentifier() }
            .filter { it.isNotEmpty() }.toHashSet()
        if (c.baseConfig.ignoredContactSources == ignoriert) return false
        c.baseConfig.ignoredContactSources = ignoriert
        return true
    }

    /**
     * Privates Handy (ohne Rosenweg Geraet; Stefan 27.11.2026: "nix Lokales auf privaten
     * Smartphones"). Dort ohne Nextcloud-Verbindung weder lokale Kontakte noch neue lokale Kontakte,
     * sondern ein Hinweis mit "Mit der Nextcloud verbinden". Geteilte Telefone bleiben, wie sie sind.
     */
    fun privat(c: Context) = !ch.rosenweg4303.cloud.RosenwegAnmeldung.geraetDa(c)

    /** Privat ohne Nextcloud-Konto: kein lokaler Speicherort fuer neue Kontakte. */
    fun ohneZiel(c: Context) = privat(c) && !aktiv(c)

    const val HINWEIS_NEU = "Neue Kontakte speichert Rosenweg Kontakte in deiner Rosenweg-Nextcloud. " +
        "Verbinde dich zuerst mit der Nextcloud."

    /** Seite "Rosenweg-Nextcloud" (Anmelden/Verbindung), wie in den Einstellungen. */
    fun verbinden(a: Activity) {
        a.startActivity(
            Intent(a, ch.rosenweg4303.cloud.AnmeldenActivity::class.java)
                .putExtra(ch.rosenweg4303.cloud.AnmeldenActivity.EXTRA_BESCHREIBUNG, BESCHREIBUNG)
        )
    }

    const val HINWEIS_FREMD = "Dieser Kontakt liegt im Telefonbuch des Handys, nicht in deiner Rosenweg-Nextcloud. " +
        "Rosenweg Kontakte zeigt auf diesem Handy nur Nextcloud-Kontakte."

    /**
     * Privates Handy: Kontakt aus einer anderen Quelle (Google, Samsung, SIM, Telefonspeicher), z.B.
     * von einer anderen App zum Ansehen/Bearbeiten geschickt? Dann nicht zeigen (aber nie loeschen).
     */
    fun fremd(c: Context, contact: org.fossify.commons.models.contacts.Contact) =
        privat(c) && contact.source != KontakteAbgleich.KONTO_NAME && !mitNextcloud(c, contact.contactId)

    /** Gehoert zum (zusammengefassten) Android-Kontakt auch ein Roh-Kontakt der Nextcloud? */
    private fun mitNextcloud(c: Context, contactId: Int): Boolean {
        val r = ContactsContract.RawContacts.CONTENT_URI
        val wo = "${ContactsContract.RawContacts.CONTACT_ID}=? AND " +
            "${ContactsContract.RawContacts.ACCOUNT_TYPE}=? AND ${ContactsContract.RawContacts.DELETED}=0"
        val spalten = arrayOf(ContactsContract.RawContacts._ID)
        return try {
            c.contentResolver.query(r, spalten, wo, arrayOf("$contactId", KONTOTYP), null)?.use { it.count > 0 } == true
        } catch (e: SecurityException) {
            Log.w(TAG, "mitNextcloud: ${e.javaClass.simpleName}")
            false
        }
    }

    /** Leere Kontaktliste auf dem privaten Handy erklaeren; null = Fossify-Standard (geteiltes Telefon). */
    fun leerText(c: Context): Pair<String, String>? = when {
        !privat(c) -> null
        !aktiv(c) -> Pair(
            "Hier erscheinen deine Adressbücher aus der Rosenweg-Nextcloud. Das Telefonbuch des Handys " +
                "(Google, Samsung, SIM) zeigt Rosenweg Kontakte nicht an. Die Verbindung zur Nextcloud fehlt noch.",
            "Mit der Nextcloud verbinden"
        )
        else -> Pair(
            "Deine Adressbücher in der Rosenweg-Nextcloud sind noch leer. Das Telefonbuch des Handys " +
                "(Google, Samsung, SIM) zeigt Rosenweg Kontakte nicht an.",
            "Neuer Kontakt"
        )
    }

    /** Nur die Gruppen der Nextcloud (CATEGORIES) - keine Gruppen anderer Konten oder lokale. */
    fun gruppen(c: Context, alle: ArrayList<Group>): ArrayList<Group> {
        val g = ContactsContract.Groups.CONTENT_URI
        val wo = "${ContactsContract.Groups.ACCOUNT_TYPE}=? AND ${ContactsContract.Groups.DELETED}=0"
        val unsere = c.contentResolver.query(g, arrayOf(ContactsContract.Groups._ID), wo, arrayOf(KONTOTYP), null)
            ?.use { cur -> generateSequence { if (cur.moveToNext()) cur.getLong(0) else null }.toSet() } ?: emptySet()
        return ArrayList(alle.filter { it.id in unsere })
    }

    /** Quelle (Kontoname) fuer neue Kontakte, wenn die Nextcloud da ist; sonst null. */
    fun standardQuelle(c: Context): String? = if (aktiv(c)) KontakteAbgleich.KONTO_NAME else null

    /** Beim Oeffnen: Hintergrund planen und (hoechstens alle 2 Minuten) gleich abgleichen; [fertig] im UI-Thread. */
    fun abgleichen(a: Activity, sofort: Boolean = false, fertig: (Boolean) -> Unit = {}) {
        if (!berechtigt(a)) return
        DavPlaner.einrichten(a, PLAN)
        // noch kein Nextcloud-Konto im Adressbuch (gleich nach der Anmeldung): immer sofort
        if (!sofort && aktiv(a) && System.currentTimeMillis() - zuletzt < ABSTAND_MS) return
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
        quellenFestlegen(c)
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
