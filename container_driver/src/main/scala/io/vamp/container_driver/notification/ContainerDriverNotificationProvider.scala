package io.vamp.container_driver.notification

import io.vamp.common.notification.{ DefaultPackageMessageResolverProvider, LoggingNotificationProvider }

trait ContainerDriverNotificationProvider extends LoggingNotificationProvider with DefaultPackageMessageResolverProvider