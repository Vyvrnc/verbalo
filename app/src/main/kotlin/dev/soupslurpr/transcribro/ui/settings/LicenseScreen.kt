package dev.soupslurpr.transcribro.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.soupslurpr.transcribro.R
import dev.soupslurpr.transcribro.ui.reusablecomposables.VerbaloCard
import dev.soupslurpr.transcribro.ui.theme.VerbaloTheme
import dev.soupslurpr.transcribro.ui.theme.VerbaloType

@Composable
fun LicenseScreen() {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
        VerbaloCard {
            SelectionContainer {
                Text(stringResource(R.string.full_license), style = VerbaloType.monoSmall, color = VerbaloTheme.colors.textSecondary)
            }
        }
    }
}

