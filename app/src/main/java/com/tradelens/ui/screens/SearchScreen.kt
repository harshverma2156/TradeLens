package com.tradelens.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.tradelens.AppContainer
import com.tradelens.data.model.SearchItem
import com.tradelens.data.repository.MarketRepository
import com.tradelens.data.repository.SettingsRepository
import com.tradelens.data.repository.friendlyMessage
import com.tradelens.data.repository.runCatchingCancellable
import com.tradelens.ui.components.CenterMessage
import com.tradelens.ui.components.Fmt
import com.tradelens.ui.components.rememberVm
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*

class SearchViewModel(private val market: MarketRepository, private val settings: SettingsRepository) : ViewModel() {
    data class State(val loading: Boolean = false, val results: List<SearchItem> = emptyList(), val error: String? = null, val searched: Boolean = false)

    val query = MutableStateFlow("")

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val state: StateFlow<State> = query
        .debounce(350)
        .map { it.trim() }
        .distinctUntilChanged()
        .flatMapLatest { q ->
            if (q.length < 2) flowOf(State())
            else flow {
                emit(State(loading = true))
                val result = runCatchingCancellable {
                    val all = market.search(q)
                    if (settings.settings.value.indiaOnlySearch)
                        all.filter { it.symbol.endsWith(".NS") || it.symbol.endsWith(".BO") || it.symbol.startsWith("^") }
                    else all
                }
                emit(result.fold({ State(results = it, searched = true) }, { State(error = it.friendlyMessage(), searched = true) }))
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), State())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(container: AppContainer, onOpenStock: (String) -> Unit, onBack: () -> Unit) {
    val vm = rememberVm { SearchViewModel(container.market, container.settings) }
    val query by vm.query.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = {
                    TextField(
                        value = query,
                        onValueChange = { vm.query.value = it },
                        placeholder = { Text("Search by name or ticker, e.g. Infosys") },
                        singleLine = true,
                        trailingIcon = {
                            if (query.isNotEmpty()) IconButton(onClick = { vm.query.value = "" }) { Icon(Icons.Filled.Close, "Clear") }
                        },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        ),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error != null -> CenterMessage(state.error!!)
                state.searched && state.results.isEmpty() -> CenterMessage("No matching stocks. Try the company name or the NSE ticker.")
                query.isBlank() -> CenterMessage("Type at least 2 letters to search NSE and BSE stocks.")
            }
            LazyColumn {
                items(state.results, key = { it.symbol }) { item ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpenStock(item.symbol) }.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(Fmt.ticker(item.symbol), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(item.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                        Text(item.exchange, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
