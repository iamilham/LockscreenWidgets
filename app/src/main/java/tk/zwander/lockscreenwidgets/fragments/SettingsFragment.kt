package tk.zwander.lockscreenwidgets.fragments

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreference
import tk.zwander.lockscreenwidgets.R
import tk.zwander.lockscreenwidgets.activities.HideForIDsActivity
import tk.zwander.lockscreenwidgets.activities.OnboardingActivity
import tk.zwander.lockscreenwidgets.util.PrefManager
import tk.zwander.lockscreenwidgets.util.isNotificationListenerActive

/**
 * The settings page.
 * Most needed logic is handled by AndroidX, but there are some cases where
 * we need to either request permissions or pass extras to Activities we launch.
 */
class SettingsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.prefs_settings, rootKey)

        findPreference<SwitchPreference>(PrefManager.KEY_HIDE_ON_NOTIFICATIONS)?.setOnPreferenceChangeListener { _, newValue ->
            if (newValue.toString().toBoolean() && !requireContext().isNotificationListenerActive) {
                OnboardingActivity.start(requireContext(), OnboardingActivity.RetroMode.NOTIFICATION)
                false
            } else true
        }

        findPreference<ListPreference>(PrefManager.KEY_OPACITY_MODE)?.setOnPreferenceChangeListener { _, newValue ->
            if (newValue.toString().toInt() == PrefManager.VALUE_OPACITY_MODE_MASKED
                && requireContext().checkCallingOrSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                OnboardingActivity.start(requireContext(), OnboardingActivity.RetroMode.STORAGE)
                false
            } else true
        }

        findPreference<Preference>("present_ids_launch")?.setOnPreferenceClickListener {
            HideForIDsActivity.start(requireContext(), HideForIDsActivity.Type.PRESENT)
            true
        }

        findPreference<Preference>("non_present_ids_launch")?.setOnPreferenceClickListener {
            HideForIDsActivity.start(requireContext(), HideForIDsActivity.Type.NON_PRESENT)
            true
        }
    }
}