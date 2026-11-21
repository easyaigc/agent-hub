package com.agenthub.android

/** Lightweight host-JVM vectors against the shipping answer builder (no Android or model task). */
fun main() {
    var passed = 0
    fun expect(name: String, expected: List<String>, selected: List<String>, text: String, async: Boolean) {
        check(approvalQuestionAnswers(selected, text, async) == expected) { name }
        passed++
    }
    val rawValues = listOf("  original answer  ", "\noriginal answer\n", "\r\n  原文\t\n第二行  \r\n", "\t🙂 é\u00a0")
    for (async in listOf(false, true)) {
        for (text in rawValues) {
            expect("raw text only ($async)", listOf(text), emptyList(), text, async)
            expect("options and raw text ($async)", if (async) listOf(text) else listOf("Two", "One", text), listOf("Two", "One"), text, async)
        }
        for (blank in listOf("", " ", "\t\r\n  ", "\u00a0\u2003")) {
            expect("no empty answer ($async)", emptyList(), emptyList(), blank, async)
            expect("blank custom retains choices ($async)", listOf("Two", "One"), listOf("Two", "One"), blank, async)
        }
    }
    val selected = mutableListOf("One")
    val captured = approvalQuestionAnswers(selected, " ", false)
    selected += "Two"
    check(captured == listOf("One")) { "Answer snapshot must not alias editable selection" }
    passed++
    println("PASS $passed approval answer vectors")
}
