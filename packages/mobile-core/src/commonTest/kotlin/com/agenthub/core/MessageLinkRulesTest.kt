package com.agenthub.core

import kotlin.test.*

class MessageLinkRulesTest {
    @Test fun localDestinationsNeverBecomePhoneWebRequests() {
        for(host in listOf("localhost","APP.LOCALHOST.","[::1]","[::]","0.0.0.0","127.9.1.2","[::ffff:127.0.0.1]"))
            assertEquals("computerWeb",MessageLinkRules.destination("https",host,false,null,false,true),host)
        assertEquals("web",MessageLinkRules.destination("https","localhost.example.com",false,null,false,true))
    }
    @Test fun unsupportedAndCredentialLinksCannotEscape() {
        for(scheme in listOf("javascript","data","intent","content"))
            assertEquals("unsupported",MessageLinkRules.destination(scheme,"example.com",false,null,false,true))
        assertEquals("unsupported",MessageLinkRules.destination("https","example.com",true,null,false,true))
        assertEquals("unsupported",MessageLinkRules.destination("https",null,false,null,false,true))
        assertEquals("unsupported",MessageLinkRules.destination(null,null,false,null,false,false))
        assertEquals("computerFile",MessageLinkRules.destination("C",null,false,null,true,false))
        assertEquals("unsupported",MessageLinkRules.destination("codex-attachment","open",false,"",false,true))
    }
}
