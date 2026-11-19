package io.legado.app.ui.book.read.config

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.lifecycle.lifecycleScope
import androidx.preference.ListPreference
import androidx.preference.Preference
import io.legado.app.R
import io.legado.app.base.BasePrefDialogFragment
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.help.koReader.AppKoReader
import io.legado.app.help.book.isLocal
import io.legado.app.lib.prefs.SwitchPreference
import io.legado.app.lib.prefs.fragment.PreferenceFragment
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.model.ReadBook
import io.legado.app.ui.config.ConfigActivity
import io.legado.app.ui.config.ConfigTag
import io.legado.app.ui.widget.number.NumberPickerDialog
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class KoReaderSyncDialog : BasePrefDialogFragment() {
    private val preferenceTag = "koReaderSyncPreference"

    override fun onStart() {
        super.onStart()
        dialog?.window?.run {
            setBackgroundDrawableResource(R.color.transparent)
            setLayout(0.9f, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val view = LinearLayout(requireContext())
        view.setBackgroundColor(requireContext().backgroundColor)
        view.id = R.id.tag1
        container?.addView(view)
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        var preferenceFragment = childFragmentManager.findFragmentByTag(preferenceTag)
        if (preferenceFragment == null) preferenceFragment = KoReaderSyncPreferenceFragment()
        childFragmentManager.beginTransaction()
            .replace(view.id, preferenceFragment, preferenceTag)
            .commit()
    }

    class KoReaderSyncPreferenceFragment : PreferenceFragment(),
        SharedPreferences.OnSharedPreferenceChangeListener {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            addPreferencesFromResource(R.xml.pref_koreader_sync)
            updateAllSummaries()
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            listView.setEdgeEffectColor(primaryColor)
        }

        override fun onResume() {
            super.onResume()
            preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
            updateAllSummaries()
        }

        override fun onPause() {
            preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
            super.onPause()
        }

        override fun onSharedPreferenceChanged(
            sharedPreferences: SharedPreferences?,
            key: String?,
        ) {
            updateAllSummaries()
            if (key == PreferKey.koReaderSyncEnabled && AppKoReader.hasCredentials) {
                if (AppConfig.koReaderSyncEnabled) pullProgress() else pushProgress()
            }
        }

        override fun onPreferenceTreeClick(preference: Preference): Boolean {
            return when (preference.key) {
                PreferKey.koReaderPagesBeforeSync -> {
                    NumberPickerDialog(requireContext())
                        .setTitle(getString(R.string.koreader_pages_before_sync))
                        .setMinValue(0)
                        .setMaxValue(999)
                        .setValue(AppConfig.koReaderPagesBeforeSync)
                        .show { value ->
                            preferenceManager.sharedPreferences?.edit()
                                ?.putString(PreferKey.koReaderPagesBeforeSync, value.toString())
                                ?.apply()
                            updatePagesSummary()
                        }
                    true
                }

                "koreaderAccount" -> {
                    startActivity(Intent(requireContext(), ConfigActivity::class.java).apply {
                        putExtra("configTag", ConfigTag.BACKUP_CONFIG)
                    })
                    true
                }

                "koReaderPushNow" -> {
                    pushProgress()
                    true
                }

                "koReaderPullNow" -> {
                    pullProgress()
                    true
                }

                else -> super.onPreferenceTreeClick(preference)
            }
        }

        private fun pushProgress() {
            if (!requireCredentials()) return
            val book = ReadBook.book?.takeIf { it.isLocal } ?: run {
                requireContext().toastOnUi(R.string.koreader_sync_only_local)
                return
            }
            lifecycleScope.launch {
                try {
                    withContext(IO) { AppKoReader.uploadBookProgress(book, interactive = true) }
                    requireContext().toastOnUi(R.string.koreader_progress_pushed)
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    AppLog.put("手动推送 KOReader 进度失败《${book.name}》", error)
                    requireContext().toastOnUi(
                        requireContext().getString(
                            R.string.koreader_sync_failed,
                            error.localizedMessage ?: "unknown",
                        )
                    )
                }
            }
        }

        private fun pullProgress() {
            if (!requireCredentials()) return
            val book = ReadBook.book?.takeIf { it.isLocal } ?: run {
                requireContext().toastOnUi(R.string.koreader_sync_only_local)
                return
            }
            lifecycleScope.launch {
                try {
                    val remote = withContext(IO) { AppKoReader.getBookProgress(book) }
                    val progress = remote?.let { withContext(IO) { AppKoReader.toBookProgress(book, it) } }
                    if (progress == null) {
                        requireContext().toastOnUi(R.string.koreader_no_remote_progress)
                        return@launch
                    }
                    if (ReadBook.setProgress(progress)) {
                        requireContext().toastOnUi(R.string.koreader_progress_pulled)
                    } else {
                        requireContext().toastOnUi(R.string.koreader_progress_already_current)
                    }
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    AppLog.put("手动拉取 KOReader 进度失败《${book.name}》", error)
                    requireContext().toastOnUi(
                        requireContext().getString(
                            R.string.koreader_sync_failed,
                            error.localizedMessage ?: "unknown",
                        )
                    )
                }
            }
        }

        private fun requireCredentials(): Boolean {
            if (AppKoReader.hasCredentials) return true
            requireContext().toastOnUi(R.string.koreader_credentials_required)
            return false
        }

        private fun updateAllSummaries() {
            updatePagesSummary()
            findPreference<Preference>("koreaderAccount")?.summary =
                if (AppKoReader.hasCredentials) AppConfig.koReaderServer
                else getString(R.string.koreader_account_summary)
            listOf(PreferKey.koReaderSyncForward, PreferKey.koReaderSyncBackward,
                PreferKey.koReaderMatchingMode).forEach { key ->
                val preference = findPreference<ListPreference>(key) ?: return@forEach
                val index = preference.findIndexOfValue(preference.value)
                if (index >= 0) preference.summary = preference.entries[index]
            }
            findPreference<SwitchPreference>(PreferKey.koReaderSyncEnabled)?.summary = getString(
                if (AppConfig.koReaderSyncEnabled) R.string.koreader_auto_sync_on
                else R.string.koreader_auto_sync_off
            )
        }

        private fun updatePagesSummary() {
            val pages = AppConfig.koReaderPagesBeforeSync
            findPreference<Preference>(PreferKey.koReaderPagesBeforeSync)?.summary = getString(
                if (pages > 0) R.string.koreader_pages_before_sync_summary
                else R.string.koreader_pages_before_sync_disabled,
                pages,
            )
        }
    }
}
