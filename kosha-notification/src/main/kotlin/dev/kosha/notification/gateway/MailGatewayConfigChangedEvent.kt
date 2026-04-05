package dev.kosha.notification.gateway

/**
 * Published by [dev.kosha.notification.service.MailGatewayConfigService]
 * whenever the admin saves a new mail gateway configuration.
 * The [ReloadableMailGateway] listens for this and swaps its underlying
 * transport atomically.
 */
class MailGatewayConfigChangedEvent
