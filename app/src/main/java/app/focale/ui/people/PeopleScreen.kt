package app.focale.ui.people

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.focale.R
import app.focale.ui.components.EmptyState
import app.focale.ui.navigation.LocalTabsNavController
import app.focale.ui.navigation.PersonRoute

/** Toutes les personnes reconnues, en grille de vignettes rondes. */
@Composable
fun PeopleScreen(onBack: () -> Unit, viewModel: PeopleViewModel = hiltViewModel()) {
    val tabsNav = LocalTabsNavController.current
    val people by viewModel.people.collectAsStateWithLifecycle()
    val showHidden by viewModel.showHidden.collectAsStateWithLifecycle()
    val analysisEnabled by viewModel.analysisEnabled.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.people_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack, shapes = IconButtonDefaults.shapes()) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = { PeopleMenu(showHidden = showHidden, onShowHiddenChange = viewModel::setShowHidden) },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        val list = people ?: return@Scaffold
        if (list.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding)) {
                EmptyState(
                    icon = Icons.Outlined.Face,
                    title = stringResource(R.string.people_empty_title),
                    message = stringResource(if (analysisEnabled) R.string.people_empty_message else R.string.people_analysis_off_message),
                    shape = MaterialShapes.Flower,
                    action = if (analysisEnabled) {
                        null
                    } else {
                        {
                            Button(onClick = viewModel::enableAnalysis, shapes = ButtonDefaults.shapes()) {
                                Text(stringResource(R.string.search_analysis_enable))
                            }
                        }
                    },
                )
            }
            return@Scaffold
        }
        val layoutDirection = LocalLayoutDirection.current
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 104.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(
                start = padding.calculateStartPadding(layoutDirection) + 12.dp,
                end = padding.calculateEndPadding(layoutDirection) + 12.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(list, key = { it.id }) { person ->
                PersonGridTile(person, onClick = { tabsNav.navigate(PersonRoute(person.id)) { launchSingleTop = true } })
            }
        }
    }
}

@Composable
private fun PeopleMenu(showHidden: Boolean, onShowHiddenChange: (Boolean) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, shapes = IconButtonDefaults.shapes()) {
            Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.action_more))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.people_show_hidden)) },
                trailingIcon = { Checkbox(checked = showHidden, onCheckedChange = null) },
                onClick = {
                    open = false
                    onShowHiddenChange(!showHidden)
                },
            )
        }
    }
}
