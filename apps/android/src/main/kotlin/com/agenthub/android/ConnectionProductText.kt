package com.agenthub.android

import androidx.compose.runtime.Composable

// These getters contain only compiled phase/mode copy; names and diagnostics are not keys.
@Composable internal fun clientConnectionText(client: MobileClient)=productText(client.connectionLabel)
@Composable internal fun clientSourceText(client: MobileClient)=productText(client.sourceLabel)
@Composable internal fun clientRunningText(client: MobileClient,count: Int?)=
    productText("%@ · 进行中 %@",clientConnectionText(client),count?.toString() ?: "—")
@Composable internal fun clientRunningDescription(client: MobileClient,count: Int?)=
    if(count==null)productText("%@，进行中数量未知",clientConnectionText(client))
    else productText("%@，%@个会话进行中",clientConnectionText(client),count)
