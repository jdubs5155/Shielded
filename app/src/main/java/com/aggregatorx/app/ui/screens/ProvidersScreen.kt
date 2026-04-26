package com.aggregatorx.app.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aggregatorx.app.data.model.Provider
import com.aggregatorx.app.ui.components.*
import com.aggregatorx.app.ui.theme.*
import com.aggregatorx.app.ui.viewmodel.ProvidersViewModel

@OptIn(ExperimentalFoundationApi::class)

@Composable
fun ProvidersScreen(
    viewModel: ProvidersViewModel = hiltViewModel()
) {
    val providers by viewModel.providers.collectAsState()
    val analyzingProviders by viewModel.analyzingProviders.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(DarkBackground, DarkSurface)
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(16.dp))
            
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Providers",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        color = CyberCyan
                    )
                    Text(
                        text = "${providers.size} configured • ${providers.count { it.isEnabled }} active",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }
                
                // Quick stats
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    QuickStatBadge(
                        value = providers.count { it.isEnabled }.toString(),
                        label = "Active",
                        color = AccentGreen
                    )
                    QuickStatBadge(
                        value = providers.count { !it.isEnabled }.toString(),
                        label = "Disabled",
                        color = TextTertiary
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            if (providers.isEmpty()) {
                EmptyProvidersState(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    // Enabled providers section
                    val enabledProviders = providers.filter { it.isEnabled }
                    if (enabledProviders.isNotEmpty()) {
                        item {
                            SectionHeader(
                                title = "Active Providers",
                                icon = Icons.Default.CheckCircle,
                                color = AccentGreen,
                                count = enabledProviders.size
                            )
                        }
                        
                        items(enabledProviders, key = { it.id }) { provider ->
                            ProviderCard(
                                provider = provider,
                                onToggle = { enabled ->
                                    viewModel.toggleProvider(provider.id, enabled)
                                },
                                onReanalyze = {
                                    viewModel.analyzeProvider(provider.id)
                                },
                                onDelete = {
                                    viewModel.deleteProvider(provider.id)
                                },
                                isAnalyzing = analyzingProviders.contains(provider.id),
                                modifier = Modifier.animateItem()
                            )
                        }
                    }
                    
                    // Disabled providers section
                    val disabledProviders = providers.filter { !it.isEnabled }
                    if (disabledProviders.isNotEmpty()) {
                        item {
                            Spacer(modifier = Modifier.height(16.dp))
                            SectionHeader(
                                title = "Disabled Providers",
                                icon = Icons.Default.Block,
                                color = TextTertiary,
                                count = disabledProviders.size
                            )
                        }
                        
                        items(disabledProviders, key = { it.id }) { provider ->
                            ProviderCard(
                                provider = provider,
                                onToggle = { enabled ->
                                    viewModel.toggleProvider(provider.id, enabled)
                                },
                                onReanalyze = {
                                    viewModel.analyzeProvider(provider.id)
                                },
                                onDelete = {
                                    viewModel.deleteProvider(provider.id)
                                },
                                isAnalyzing = analyzingProviders.contains(provider.id),
                                modifier = Modifier
                                    .animateItem()
                                    .alpha(0.6f)
                            )
                        }
                    }
                    
                    item {
                        Spacer(modifier = Modifier.height(80.dp))
                    }
                }
            }
        }
        
        // Messages
        uiState.message?.let { message ->
            Snackbar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
                containerColor = AccentGreen.copy(alpha = 0.9f),
                contentColor = DarkBackground,
                action = {
                    TextButton(onClick = viewModel::clearMessage) {
                        Text("OK", color = DarkBackground)
                    }
                }
            ) {
                Text(message)
            }
        }
        
        uiState.error?.let { error ->
            Snackbar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
                containerColor = AccentRed.copy(alpha = 0.9f),
                contentColor = TextPrimary,
                action = {
                    TextButton(onClick = viewModel::clearError) {
                        Text("Dismiss", color = TextPrimary)
                    }
                }
            ) {
                Text(error)
            }
        }
    }
}

@Composable
fun QuickStatBadge(
    value: String,
    label: String,
    color: androidx.compose.ui.graphics.Color
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.15f)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                color = color,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = label,
                color = color.copy(alpha = 0.8f),
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
fun SectionHeader(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: androidx.compose.ui.graphics.Color,
    count: Int
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = color,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.width(8.dp))
        Surface(
            shape = RoundedCornerShape(4.dp),
            color = color.copy(alpha = 0.2f)
        ) {
            Text(
                text = count.toString(),
                color = color,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}

@Composable
fun EmptyProvidersState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Outlined.Dns,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = TextTertiary.copy(alpha = 0.5f)
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "No providers configured",
            style = MaterialTheme.typography.headlineSmall,
            color = TextSecondary
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Go to Settings to add custom URLs\nand start aggregating content",
            style = MaterialTheme.typography.bodyMedium,
            color = TextTertiary,
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        Card(
            colors = CardDefaults.cardColors(containerColor = DarkCard),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.Lightbulb,
                    contentDescription = null,
                    tint = AccentYellow,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Quick Tip",
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Add any website URL in Settings.\nOur analyzer will automatically detect\nsearch patterns and content structure.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
