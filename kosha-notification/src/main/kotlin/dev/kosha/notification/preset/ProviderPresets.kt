package dev.kosha.notification.preset

import dev.kosha.notification.dto.ProviderPreset
import dev.kosha.notification.dto.RegionOption

/**
 * The hardcoded list of mail gateway provider presets the admin UI can pick from.
 * Each preset tells the frontend which fields to show and what defaults to fill.
 *
 * Adding a new provider:
 *   1. Add it to [ALL]
 *   2. If its host varies by region, add [RegionOption]s
 *   3. If it has a fixed username (e.g. 'apikey'), set [ProviderPreset.fixedUsername]
 *   4. If the admin needs special instructions, put them in [ProviderPreset.warning]
 *
 * See [research notes on transactional + enterprise providers in session memory]
 * for why v1 sticks to SMTP transport for all providers.
 */
object ProviderPresets {

    val MAILPIT = ProviderPreset(
        key = "mailpit",
        label = "Mailpit (development)",
        description = "Local SMTP capture for development — no real emails are sent.",
        transport = "smtp",
        defaultHost = "localhost",
        defaultPort = 1025,
        defaultEncryption = "none",
        showUsername = false,
        showPassword = false,
        devOnly = true,
    )

    val SMTP_GENERIC = ProviderPreset(
        key = "smtp_generic",
        label = "Internal SMTP relay (no auth)",
        description = "An internal corporate relay that accepts mail from allowlisted IPs without authentication.",
        transport = "smtp",
        defaultHost = null,
        defaultPort = 25,
        defaultEncryption = "none",
        showUsername = false,
        showPassword = false,
        hostHint = "e.g. smtp.internal.example.com",
    )

    val SMTP_AUTH = ProviderPreset(
        key = "smtp_auth",
        label = "SMTP server (username/password)",
        description = "Generic authenticated SMTP. Works for on-premises Exchange, hosted SMTP providers, and most other servers.",
        transport = "smtp",
        defaultHost = null,
        defaultPort = 587,
        defaultEncryption = "starttls",
        showSkipTlsVerify = true,
        hostHint = "e.g. mail.example.com",
    )

    val SES = ProviderPreset(
        key = "ses",
        label = "Amazon SES",
        description = "Amazon Simple Email Service via SMTP. New accounts start in sandbox mode (send to verified addresses only) until production access is granted.",
        transport = "smtp",
        defaultHost = "email-smtp.us-east-1.amazonaws.com",
        defaultPort = 587,
        defaultEncryption = "starttls",
        showRegion = true,
        regions = listOf(
            RegionOption("us-east-1", "US East (N. Virginia)", "email-smtp.us-east-1.amazonaws.com"),
            RegionOption("us-east-2", "US East (Ohio)", "email-smtp.us-east-2.amazonaws.com"),
            RegionOption("us-west-1", "US West (N. California)", "email-smtp.us-west-1.amazonaws.com"),
            RegionOption("us-west-2", "US West (Oregon)", "email-smtp.us-west-2.amazonaws.com"),
            RegionOption("eu-west-1", "Europe (Ireland)", "email-smtp.eu-west-1.amazonaws.com"),
            RegionOption("eu-west-2", "Europe (London)", "email-smtp.eu-west-2.amazonaws.com"),
            RegionOption("eu-central-1", "Europe (Frankfurt)", "email-smtp.eu-central-1.amazonaws.com"),
            RegionOption("eu-north-1", "Europe (Stockholm)", "email-smtp.eu-north-1.amazonaws.com"),
            RegionOption("ap-south-1", "Asia Pacific (Mumbai)", "email-smtp.ap-south-1.amazonaws.com"),
            RegionOption("ap-northeast-1", "Asia Pacific (Tokyo)", "email-smtp.ap-northeast-1.amazonaws.com"),
            RegionOption("ap-southeast-1", "Asia Pacific (Singapore)", "email-smtp.ap-southeast-1.amazonaws.com"),
            RegionOption("ap-southeast-2", "Asia Pacific (Sydney)", "email-smtp.ap-southeast-2.amazonaws.com"),
            RegionOption("ca-central-1", "Canada (Central)", "email-smtp.ca-central-1.amazonaws.com"),
            RegionOption("sa-east-1", "South America (São Paulo)", "email-smtp.sa-east-1.amazonaws.com"),
        ),
        warning = "Credentials are IAM SMTP credentials, not regular AWS access keys. Generate them in the SES console under 'SMTP settings'.",
    )

    val GWS_RELAY = ProviderPreset(
        key = "gws_relay",
        label = "Google Workspace SMTP relay",
        description = "Google Workspace SMTP relay service. Admin must enable the service and add the Kosha server's public IP to the allowlist.",
        transport = "smtp",
        defaultHost = "smtp-relay.gmail.com",
        defaultPort = 587,
        defaultEncryption = "starttls",
        showUsername = false,
        showPassword = false,
        warning = "Configure at: Google Admin → Apps → Google Workspace → Gmail → Routing → SMTP relay service. Add your Kosha server's egress IP to the allowlist.",
    )

    val GMAIL_APP = ProviderPreset(
        key = "gmail_app",
        label = "Gmail (app password)",
        description = "Send from a single Gmail or Google Workspace mailbox using an app password. Limited to ~500-2000 emails/day depending on plan.",
        transport = "smtp",
        defaultHost = "smtp.gmail.com",
        defaultPort = 587,
        defaultEncryption = "starttls",
        warning = "The account must have 2-Step Verification enabled. Generate an app password at myaccount.google.com/apppasswords and use your full Gmail address as the username.",
    )

    val M365_LEGACY = ProviderPreset(
        key = "m365_legacy",
        label = "Microsoft 365 (SMTP — legacy)",
        description = "Microsoft 365 / Exchange Online via SMTP AUTH. Requires admin to explicitly enable SMTP AUTH on the sending mailbox.",
        transport = "smtp",
        defaultHost = "smtp.office365.com",
        defaultPort = 587,
        defaultEncryption = "starttls",
        warning = "SMTP AUTH is disabled by default in M365 tenants since 2022. An admin must enable it per-mailbox via Set-CASMailbox -SmtpClientAuthenticationDisabled \$false. Accounts with MFA require an app password. Graph API with OAuth 2.0 support is planned for v2.",
    )

    val SENDGRID = ProviderPreset(
        key = "sendgrid_smtp",
        label = "SendGrid",
        description = "Twilio SendGrid via SMTP relay. Sender domain or individual sender must be verified in the SendGrid dashboard.",
        transport = "smtp",
        defaultHost = "smtp.sendgrid.net",
        defaultPort = 587,
        defaultEncryption = "starttls",
        fixedUsername = "apikey",
        showUsername = false,
        showRegion = true,
        regions = listOf(
            RegionOption("us", "Global (US)", "smtp.sendgrid.net"),
            RegionOption("eu", "EU", "smtp.eu.sendgrid.net"),
        ),
        warning = "Username is automatically set to 'apikey'. Paste your SendGrid API key into the password field.",
    )

    val MAILGUN = ProviderPreset(
        key = "mailgun_smtp",
        label = "Mailgun",
        description = "Mailgun via SMTP relay. Use per-domain SMTP credentials from your Mailgun dashboard.",
        transport = "smtp",
        defaultHost = "smtp.mailgun.org",
        defaultPort = 587,
        defaultEncryption = "starttls",
        showRegion = true,
        regions = listOf(
            RegionOption("us", "US", "smtp.mailgun.org"),
            RegionOption("eu", "EU", "smtp.eu.mailgun.org"),
        ),
        warning = "Use the SMTP credentials from the 'Domain settings' page in Mailgun — not your account password.",
    )

    val POSTMARK = ProviderPreset(
        key = "postmark_smtp",
        label = "Postmark",
        description = "Postmark transactional email via SMTP. Strictly transactional traffic only — marketing emails require a separate broadcast stream.",
        transport = "smtp",
        defaultHost = "smtp.postmarkapp.com",
        defaultPort = 587,
        defaultEncryption = "starttls",
        warning = "Use your Server API Token as both the username and password.",
    )

    val SPARKPOST = ProviderPreset(
        key = "sparkpost_smtp",
        label = "SparkPost",
        description = "SparkPost transactional email via SMTP.",
        transport = "smtp",
        defaultHost = "smtp.sparkpostmail.com",
        defaultPort = 587,
        defaultEncryption = "starttls",
        fixedUsername = "SMTP_Injection",
        showUsername = false,
        showRegion = true,
        regions = listOf(
            RegionOption("us", "US", "smtp.sparkpostmail.com"),
            RegionOption("eu", "EU", "smtp.eu.sparkpostmail.com"),
        ),
        warning = "Username is automatically set to 'SMTP_Injection'. Paste your SparkPost API key into the password field.",
    )

    val MAILJET = ProviderPreset(
        key = "mailjet_smtp",
        label = "Mailjet",
        description = "Mailjet via SMTP relay.",
        transport = "smtp",
        defaultHost = "in-v3.mailjet.com",
        defaultPort = 587,
        defaultEncryption = "starttls",
        warning = "Use your Mailjet API Key (public) as the username and Secret Key (private) as the password.",
    )

    val ALL: List<ProviderPreset> = listOf(
        MAILPIT,
        SMTP_GENERIC,
        SMTP_AUTH,
        SES,
        GWS_RELAY,
        GMAIL_APP,
        M365_LEGACY,
        SENDGRID,
        MAILGUN,
        POSTMARK,
        SPARKPOST,
        MAILJET,
    )

    fun findByKey(key: String): ProviderPreset? = ALL.firstOrNull { it.key == key }
}
