package com.eda.choreography.infra.plan;

import com.eda.choreography.domain.plan.Conditions;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;

/**
 * Evaluates a choice's condition as a Spring Expression Language (SpEL) expression. The step's
 * result is the variable {@code #result}, so a condition reads like
 * {@code #result['risk'] == 'HIGH'}.
 *
 * <p>SpEL rather than a language of my own: it is a maintained library on the classpath
 * already, with comparisons, boolean logic, null handling and nested map access worked out and
 * tested. A small custom parser would have to grow all of that, and every edge case would be
 * mine to get wrong.
 *
 * <p>Conditions come from a drawn flow, so they are evaluated in a read-only
 * {@link SimpleEvaluationContext}. It has no type references, no constructors, no method calls
 * and no assignment, so a condition can read the result but cannot run code in the service.
 *
 * <p>Parsed expressions are cached by their text; one is evaluated many times, by many
 * threads, which SpEL expressions allow.
 */
public final class SpelConditions implements Conditions {

    private final SpelExpressionParser parser = new SpelExpressionParser();
    private final Map<String, Expression> parsed = new ConcurrentHashMap<>();

    @Override
    public boolean holds(String condition, Map<String, Object> result) {
        var expression = parsed.computeIfAbsent(condition, parser::parseExpression);
        var context = SimpleEvaluationContext.forReadOnlyDataBinding().build();
        context.setVariable("result", result);
        // Read untyped: asking SpEL for a Boolean would convert the string "true" into one.
        if (!(expression.getValue(context) instanceof Boolean value)) {
            throw new IllegalArgumentException("condition " + condition + " is neither true nor false for " + result);
        }
        return value;
    }
}
