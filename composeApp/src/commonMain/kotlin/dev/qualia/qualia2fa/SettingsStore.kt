package dev.qualia.qualia2fa

/** Which color scheme the interface uses. */
enum class AppTheme {
    /** The platform's own light or dark setting, followed as it changes. */
    System,

    /** The light scheme regardless of the platform setting. */
    Light,

    /** The dark scheme regardless of the platform setting. */
    Dark,
    ;

    companion object {
        /** The value named [name], or [System] for an absent or unknown name. */
        fun fromName(name: String?): AppTheme = entries.firstOrNull { it.name == name } ?: System
    }
}

/**
 * Where the user's preferences are kept.
 *
 * Separate from [VaultStorage] on purpose: losing these costs a language and a theme choice,
 * while losing that file costs every entry. Keeping them apart also means a settings file that
 * cannot be written never stands between the user and the vault.
 */
interface SettingsStore {
    /** The language the user chose, or [AppLanguage.System] when nothing was chosen yet. */
    fun language(): AppLanguage

    /** Remembers the language the user chose. */
    fun setLanguage(language: AppLanguage)

    /** The theme the user chose, or [AppTheme.System] when nothing was chosen yet. */
    fun theme(): AppTheme

    /** Remembers the theme the user chose. */
    fun setTheme(theme: AppTheme)

    /**
     * The group tree a 0.2.x installation kept beside the preferences, or `null`.
     *
     * Read **once**, to migrate it into the vault; nothing writes the tree here any more. The
     * text is the old `Q2FAGL1` form, which names entries and groups by identifier and holds no
     * secret.
     */
    fun legacyGroupLayout(): String?

    /**
     * Deletes the migrated tree from the preferences.
     *
     * Called only after the tree has been written into the vault: losing the arrangement because
     * a write failed would turn a migration into data loss.
     */
    fun clearLegacyGroupLayout()
}
