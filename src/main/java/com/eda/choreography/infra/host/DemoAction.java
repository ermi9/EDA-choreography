package com.eda.choreography.infra.host;

import com.eda.choreography.domain.message.ChoreographyMessage;
import com.eda.choreography.domain.step.StepAction;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * DEMO, not real work: what a hosted service does for a step, so drawn flows can be run and
 * watched without writing services. Its result is the instance's input plus the service, the
 * step and its {@code visits}, how many times this step has run in the instance counting this
 * one. Conditions in a drawn flow can test input fields ({@code #result['amount'] > 1000}), and
 * a loop can end on its visits ({@code #result['visits'] >= 3}).
 *
 * <p>Three input fields steer it: {@code failAt}, a step or service that throws, to watch an
 * instance being undone; {@code failAtVisit}, which makes it throw only on that visit, to watch a
 * loop undone across its rounds; and {@code delayMs}, a pause per step (up to 5 s), to watch a
 * join wait or time out.
 */
final class DemoAction implements StepAction {

    private static final long LONGEST_DELAY_MS = 5_000;

    private final String service;

    DemoAction(String service) {
        this.service = service;
    }

    @Override
    public Map<String, ?> execute(ChoreographyMessage message) {
        var step = message.step() != null ? message.step() : service;
        var visits = message.runsOf(step) + 1;
        var failAt = message.input().get("failAt");
        var failAtVisit = message.input().get("failAtVisit");
        if ((step.equals(failAt) || service.equals(failAt))
                && (!(failAtVisit instanceof Number visit) || visit.intValue() == visits)) {
            throw new IllegalStateException("demo: " + step + " was asked to fail");
        }
        if (message.input().get("delayMs") instanceof Number delay) {
            pause(Math.min(delay.longValue(), LONGEST_DELAY_MS));
        }
        var result = new LinkedHashMap<String, Object>(message.input());
        result.remove("failAt");
        result.remove("failAtVisit");
        result.remove("delayMs");
        result.put("service", service);
        result.put("step", step);
        result.put("visits", visits);
        return result;
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted during a demo pause", e);
        }
    }
}
