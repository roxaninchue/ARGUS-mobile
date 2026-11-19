package com.argus.assistant.connect

import com.argus.assistant.BrandName

/**
 * Account lock: throwaway (disposable) email addresses can't hold an ARGUS account. blaze-auth 0.10.0 has the final say
 * (the disposable-email-domains list, 10,274 domains, plus an MX check: sign-up 400 / sign-in 403 disposable_email);
 * this is the phone's own first check, so the sign-up form says so before anything is sent. [DOMAINS] holds the most
 * used throwaway services, each one also on the server's list. A domain matches itself and every subdomain
 * (mail.mailinator.com counts as mailinator.com), as on the server. Pure.
 */
object ThrowawayEmail {
    val DOMAINS: Set<String> = """
        10minutemail.co.uk 10minutemail.com 10minutemail.net 1secmail.com 1secmail.net 1secmail.org 20minutemail.com
        30minutemail.com anonbox.net byom.de courriel.fr.nf crazymailing.com cs.email discard.email discardmail.com
        discardmail.de dispostable.com dropmail.me einrot.com email-temp.com emailfake.com emailondeck.com
        emailtemporanea.com emailtemporanea.net emltmp.com esiix.com fakeinbox.com fakemail.net fexbox.org
        fexpost.com getairmail.com getnada.com grr.la guerrillamail.biz guerrillamail.com guerrillamail.de
        guerrillamail.net guerrillamail.org guerrillamailblock.com harakirimail.com inboxkitten.com incognitomail.org
        jetable.fr.nf kasmail.com laste.ml mail.tm mailbox.in.ua mailbox52.ga mailcatch.com maildrop.cc
        mailexpire.com mailforspam.com mailinator.com mailmoat.com mailnesia.com mailnull.com mailpoof.com
        mailsac.com mailtemp.info mailto.plus mintemail.com minuteinbox.com moakt.cc moakt.com mohmal.com
        moncourrier.fr.nf monemail.fr.nf monmail.fr.nf mt2015.com mvrht.com mytemp.email mytrashmail.com nada.email
        objectmail.com pokemail.net proxymail.eu rcpt.at rover.info sharklasers.com spam4.me spambox.us spamex.com
        spamfree24.org spamherelots.com temp-mail.org tempail.com tempemail.net tempinbox.com tempmail.de
        tempmail.plus tempmailer.com tempmailo.com tempomail.fr tempr.email thankyou2010.com throwawaymail.com
        tmail.ws tmpbox.net tmpeml.com tmpmail.net tmpmail.org trash-mail.com trashmail.com trashmail.de trashmail.io
        trashmail.net trashymail.com trbvm.com vomoto.com wegwerfmail.de wegwerfmail.net wegwerfmail.org wwjmp.com
        xojxe.com yoggm.com yopmail.com yopmail.fr yopmail.net zehnminutenmail.de zetmail.com
    """.trim().split(Regex("\\s+")).toSet()

    const val MESSAGE = "That looks like a throwaway email address. Use your own email: ${BrandName.UPPER} sends a code there, and it is how you get back into your account."

    /** The address's domain, lowercased, without a trailing dot; "" when there is none. */
    fun domainOf(email: String): String {
        val at = email.lastIndexOf('@')
        return if (at < 0) "" else email.substring(at + 1).trim().lowercase().trimEnd('.')
    }

    /** True when the domain or one of its parent domains is on [list]. */
    fun isThrowaway(email: String, list: Set<String> = DOMAINS): Boolean {
        val parts = domainOf(email).split('.').filter { it.isNotEmpty() }
        for (i in 0 until parts.size - 1) if (parts.subList(i, parts.size).joinToString(".") in list) return true
        return false
    }

    /** "" when the address may be used, else the sentence to show. */
    fun check(email: String): String = if (isThrowaway(email)) MESSAGE else ""
}
