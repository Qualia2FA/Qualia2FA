package dev.qualia.qualia2fa

/**
 * How an entry's fields are read when the source format had no account column.
 *
 * # The shape being repaired
 *
 * Aegis exports write the account (an e-mail address, a username) in `name` and the service in
 * `issuer`; its formats have no third field for the account. The core's import mapping therefore
 * produces `name = account`, `issuer = service`, `account = ""`. That is faithful to the file,
 * but it reads backwards in this application: the card's title is the account, and the edit form
 * shows the account nowhere.
 *
 * An entry that has an empty account and a name that differs from its issuer is read as that
 * shape: the service becomes the title, the original name becomes the account, and saving the
 * edit form writes the three fields in their corrected places. Entries that already carry an
 * account, or whose name and issuer agree, are left exactly as stored.
 *
 * The values are **presented**, not rewritten on read: a file exported back to Aegis still has
 * the account in `name`, which is where that format expects it. Saving the edit form is what
 * turns the presentation into the stored form.
 */
private fun isAccountOnly(
    name: String,
    issuer: String,
    account: String,
): Boolean =
    account.isBlank() &&
        issuer.isNotBlank() &&
        name.isNotBlank() &&
        !name.equals(issuer, ignoreCase = true)

/** The title to show for an entry, in the card and in the edit form. */
internal fun presentedEntryName(
    name: String,
    issuer: String,
    account: String,
): String = if (isAccountOnly(name, issuer, account)) issuer else name

/** The secondary line to show under an entry's title, when there is one. */
internal fun presentedEntryDetail(
    name: String,
    issuer: String,
    account: String,
): String? =
    when {
        isAccountOnly(name, issuer, account) -> name.takeIf { it.isNotBlank() }
        // A name that already is the service would be shown twice; the account is the useful
        // second line then. This is also the shape an imported entry takes after its first save.
        issuer.isNotBlank() && !issuer.equals(name, ignoreCase = true) -> issuer
        else -> account.takeIf { it.isNotBlank() }
    }

/** The account field to prefill in the edit form. */
internal fun presentedEntryAccount(
    name: String,
    issuer: String,
    account: String,
): String = if (isAccountOnly(name, issuer, account)) name else account

/** The title [Entry] as the interface presents it. */
internal fun Entry.presentedName(): String = presentedEntryName(name, issuer, account)

/** The secondary line [Entry] as the interface presents it. */
internal fun Entry.presentedDetail(): String? = presentedEntryDetail(name, issuer, account)

/** The account field [Entry] as the edit form presents it. */
internal fun Entry.presentedAccount(): String = presentedEntryAccount(name, issuer, account)

/** The title [ImportedEntry] as the preview presents it. */
internal fun ImportedEntry.presentedName(): String = presentedEntryName(name, issuer, account)

/** The secondary line [ImportedEntry] as the preview presents it. */
internal fun ImportedEntry.presentedDetail(): String? = presentedEntryDetail(name, issuer, account)

/** The account field [ImportedEntry] would prefill, should it be edited. */
internal fun ImportedEntry.presentedAccount(): String = presentedEntryAccount(name, issuer, account)
