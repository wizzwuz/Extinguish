package own.moderpach.extinguish.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraphBuilder
import own.moderpach.extinguish.ExtinguishNavGraph
import own.moderpach.extinguish.ExtinguishNavRoute
import own.moderpach.extinguish.R
import own.moderpach.extinguish.settings.components.EnablerCard
import own.moderpach.extinguish.settings.components.RadioCard
import own.moderpach.extinguish.settings.components.SettingCard
import own.moderpach.extinguish.settings.components.SettingLazyColumn
import own.moderpach.extinguish.settings.components.SettingListItem
import own.moderpach.extinguish.settings.components.SettingListItemWithSwitch
import own.moderpach.extinguish.settings.data.ISettingsRepository
import own.moderpach.extinguish.settings.data.SettingsTokens
import own.moderpach.extinguish.settings.test.FakeSettingsRepository
import own.moderpach.extinguish.ui.components.ExtinguishTopAppBarWithNavigationBack
import own.moderpach.extinguish.ui.navigation.extinguishComposable
import own.moderpach.extinguish.ui.theme.ExtinguishTheme

val ExtinguishNavGraph.VolumeKeyControl: ExtinguishNavRoute get() = "VolumeKeyControl"

fun NavGraphBuilder.volumeKeyControl(
    onBack: () -> Unit,
    settingsRepository: ISettingsRepository,
    onNavigateTo: (ExtinguishNavRoute) -> Unit
) = extinguishComposable(
    ExtinguishNavGraph.VolumeKeyControl,
) {
    VolumeKeyControlScreen(
        onBack,
        settingsRepository,
        onNavigateTo
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VolumeKeyControlScreen(
    onBack: () -> Unit,
    settingsRepository: ISettingsRepository,
    onNavigateTo: (ExtinguishNavRoute) -> Unit
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var showPasswordDialog by remember { mutableStateOf(false) }
    var newPin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    if (showPasswordDialog) {
        AlertDialog(
            onDismissRequest = {
                showPasswordDialog = false
                newPin = ""
                confirmPin = ""
                errorMessage = null
            },
            title = {
                Text(stringResource(R.string.str_change_password_dialog_title))
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newPin,
                        onValueChange = {
                            if (it.all { char -> char.isDigit() }) newPin = it
                            errorMessage = null
                        },
                        label = { Text(stringResource(R.string.str_enter_new_pin)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                    )
                    OutlinedTextField(
                        value = confirmPin,
                        onValueChange = {
                            if (it.all { char -> char.isDigit() }) confirmPin = it
                            errorMessage = null
                        },
                        label = { Text(stringResource(R.string.str_confirm_pin)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                    )
                    errorMessage?.let {
                        Text(
                            text = it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                val emptyPinStr = stringResource(R.string.str_pin_empty)
                val mismatchPinStr = stringResource(R.string.str_pin_mismatch)
                TextButton(
                    onClick = {
                        if (newPin.isEmpty()) {
                            errorMessage = emptyPinStr
                        } else if (newPin != confirmPin) {
                            errorMessage = mismatchPinStr
                        } else {
                            settingsRepository.volumeKeyEvent.overlayPassword = newPin
                            showPasswordDialog = false
                            newPin = ""
                            confirmPin = ""
                            errorMessage = null
                        }
                    }
                ) {
                    Text(stringResource(R.string.Save))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showPasswordDialog = false
                        newPin = ""
                        confirmPin = ""
                        errorMessage = null
                    }
                ) {
                    Text(stringResource(R.string.str_cancel))
                }
            }
        )
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets.union(WindowInsets.displayCutout),
        topBar = {
            ExtinguishTopAppBarWithNavigationBack(
                onBack = onBack,
                titleString = stringResource(R.string.Volume_key_control),
                scrollBehavior = scrollBehavior
            )
        },
    ) { innerPadding ->
        SettingLazyColumn(
            contentPadding = innerPadding
        ) {
            item {
                EnablerCard(
                    text = stringResource(R.string.Enabled),
                    checked = settingsRepository.volumeKeyEvent.enabled,
                    onCheckedChange = {
                        settingsRepository.volumeKeyEvent.enabled = it
                    }
                )
            }
            item {
                SettingCard {
                    SettingListItemWithSwitch(
                        headline = stringResource(R.string.str_clickToTurnScreenOn),
                        checked = settingsRepository.volumeKeyEvent.clickToTurnScreenOn,
                        onCheckedChange = {
                            settingsRepository.volumeKeyEvent.clickToTurnScreenOn = it
                        }
                    )
                    SettingListItemWithSwitch(
                        headline = stringResource(R.string.str_clickToTurnScreenOff),
                        checked = settingsRepository.volumeKeyEvent.clickToTurnScreenOff,
                        onCheckedChange = {
                            settingsRepository.volumeKeyEvent.clickToTurnScreenOff = it
                        }
                    )
                }
            }
            if (settingsRepository.solution == SettingsTokens.SolutionValue.ShizukuPowerOffScreen) {
                item {
                    SettingCard {
                        SettingListItemWithSwitch(
                            headline = stringResource(R.string.str_overlay_password),
                            supporting = stringResource(R.string.str_overlay_password_supporting),
                            checked = settingsRepository.volumeKeyEvent.overlayPasswordEnabled,
                            onCheckedChange = {
                                settingsRepository.volumeKeyEvent.overlayPasswordEnabled = it
                            }
                        )
                        if (settingsRepository.volumeKeyEvent.overlayPasswordEnabled) {
                            SettingListItem(
                                headline = stringResource(R.string.str_change_overlay_password),
                                supporting = stringResource(R.string.str_change_overlay_password_supporting),
                                onClick = {
                                    showPasswordDialog = true
                                }
                            )
                        }
                    }
                }
            }
            item {
                SettingCard(
                    contentPadding = PaddingValues(bottom = 8.dp)
                ) {
                    SettingListItem(
                        headline = stringResource(R.string.str_volumeKeyListeningMethod)
                    )
                    Column(
                        Modifier.padding(horizontal = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        RadioCard(
                            modifier = Modifier.selectableGroup(),
                            selected = settingsRepository.volumeKeyEvent.listeningMethod == SettingsTokens.VolumeKeyEvent.ListeningMethodValue.Shell,
                            onClick = {
                                settingsRepository.volumeKeyEvent.listeningMethod =
                                    SettingsTokens.VolumeKeyEvent.ListeningMethodValue.Shell
                            },
                            headline = stringResource(R.string.str_shell),
                            supporting = stringResource(R.string.str_shell_supporting)
                        )
                        RadioCard(
                            modifier = Modifier.selectableGroup(),
                            selected = settingsRepository.volumeKeyEvent.listeningMethod == SettingsTokens.VolumeKeyEvent.ListeningMethodValue.Window,
                            onClick = {
                                settingsRepository.volumeKeyEvent.listeningMethod =
                                    SettingsTokens.VolumeKeyEvent.ListeningMethodValue.Window
                            },
                            headline = stringResource(R.string.str_android_window),
                            supporting = stringResource(R.string.str_android_window_supporting)
                        )
                    }
                }
            }
        }

    }
}

@Preview
@Composable
private fun VolumeKeyControlScreenPreview() = ExtinguishTheme {
    Box(modifier = Modifier.background(MaterialTheme.colorScheme.background)) {
        VolumeKeyControlScreen(
            onBack = {},
            settingsRepository = FakeSettingsRepository()
        ) { }
    }
}
