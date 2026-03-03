package io.github.riken127.app.resolver;

import com.netflix.graphql.dgs.*;
import io.github.riken127.core.alert.AlertEngine;
import io.github.riken127.core.domain.AlertEvent;
import io.github.riken127.core.domain.PriceAlert;
import io.github.riken127.core.domain.PriceAlertInput;
import lombok.extern.log4j.Log4j2;
import org.reactivestreams.Publisher;

import java.util.List;

@DgsComponent
@Log4j2
public class AlertResolver {

    private final AlertEngine alertEngine;

    public AlertResolver(AlertEngine alertEngine) {
        this.alertEngine = alertEngine;
    }

    @DgsQuery
    public List<PriceAlert> myAlerts() {
        // In prod: filter by authenticated user — replace with SecurityContext lookup
        return alertEngine.getActiveAlerts();
    }

    @DgsMutation
    public PriceAlert setPriceAlert(@InputArgument PriceAlertInput input) {
        log.info("[Alert] Creating alert — symbol={} direction={} target={}",
                input.getSymbol(), input.getDirection(), input.getTargetPrice());

        return alertEngine.registerAlert(
                input.getSymbol(),
                input.getTargetPrice(),
                input.getDirection()
        );
    }

    @DgsMutation
    public boolean cancelPriceAlert(@InputArgument String alertId) {
        boolean cancelled = alertEngine.cancelAlert(alertId);

        if (!cancelled) {
            log.warn("[Alert] Cancel failed — id={} not found or already inactive", alertId);
        }

        return cancelled;
    }

    @DgsSubscription
    public Publisher<AlertEvent> priceAlertTriggered(@InputArgument String alertId) {
        log.info("[Alert] Client subscribed to alert — id={}", alertId);

        return alertEngine.streamAlert(alertId)
                .doOnSuccess(event -> {
                    if (event != null) {
                        log.info("[Alert] Delivering trigger event to client — id={} price={}",
                                alertId, event.getTriggerPrice());
                    }
                })
                .doOnCancel(() -> log.info("[Alert] Client unsubscribed from alert — id={}", alertId))
                .flux(); // Mono → Publisher for DGS subscription
    }
}
