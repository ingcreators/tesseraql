package io.tesseraql.compiler.binding;

import io.tesseraql.yaml.model.Binding;
import io.tesseraql.yaml.model.DecisionUse;
import io.tesseraql.yaml.model.ErrorsSpec;
import io.tesseraql.yaml.model.NotifySpec;
import io.tesseraql.yaml.model.OutboxSpec;
import io.tesseraql.yaml.model.PublishSpec;
import io.tesseraql.yaml.model.RouteDefinition;
import io.tesseraql.yaml.model.ValidationRule;
import java.util.Map;

/**
 * Everything a command document declares that rides its one transaction: the statements, the
 * rules and decisions checked before them, and the messages published after them.
 *
 * <p>These seven arrived at {@link TransactionalCommandProcessor} as seven positional arguments
 * read one by one off the same {@code RouteDefinition}, which is what {@link #of} does in one
 * place instead. A synthesized workflow transition builds one directly, because its route is not
 * a document anyone wrote.
 *
 * @param steps    the ordered statements, keyed by step id — the one spelling a command's write
 *                 has (docs/unified-sources.md decision 8)
 * @param validate the declarative validation rules, keyed by rule id
 * @param decide   the decision tables evaluated once before the rules
 * @param notifications the notifications, keyed by notification id ("notify" is not a
 *                 legal record component name — it would hide {@code Object.notify()})
 * @param outbox   the transactional outbox declaration, or null
 * @param publish  the domain event published through that outbox, or null
 * @param errors   how a constraint violation is reported, or null for the default
 */
public record CommandDeclaration(Map<String, Binding> steps,
        Map<String, ValidationRule> validate, Map<String, DecisionUse> decide,
        Map<String, NotifySpec> notifications, OutboxSpec outbox, PublishSpec publish,
        ErrorsSpec errors) {

    /** An absent map is an empty one, so the processor never has to ask. */
    public CommandDeclaration {
        steps = steps == null ? Map.of() : steps;
        validate = validate == null ? Map.of() : validate;
        decide = decide == null ? Map.of() : decide;
        notifications = notifications == null ? Map.of() : notifications;
    }

    /** What the document declares, read once. */
    public static CommandDeclaration of(RouteDefinition definition) {
        return new CommandDeclaration(definition.steps(), definition.validate(),
                definition.decide(), definition.notifications(), definition.outbox(),
                definition.publish(), definition.errors());
    }
}
