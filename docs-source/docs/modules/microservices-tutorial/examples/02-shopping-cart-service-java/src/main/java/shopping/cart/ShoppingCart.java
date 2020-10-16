package shopping.cart;

import akka.actor.typed.ActorRef;
import akka.actor.typed.ActorSystem;
import akka.actor.typed.Behavior;
import akka.actor.typed.SupervisorStrategy;
import akka.actor.typed.javadsl.Behaviors;
import akka.cluster.sharding.typed.javadsl.ClusterSharding;
import akka.cluster.sharding.typed.javadsl.Entity;
import akka.cluster.sharding.typed.javadsl.EntityContext;
import akka.cluster.sharding.typed.javadsl.EntityTypeKey;
import akka.pattern.StatusReply;
import akka.persistence.typed.PersistenceId;
import akka.persistence.typed.javadsl.*;
import com.fasterxml.jackson.annotation.JsonCreator;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * This is an event sourced actor (`EventSourcedBehavior`). An entity managed by Cluster Sharding.
 *
 * It has a state, [[ShoppingCart.State]], which holds the current shopping cart items
 * and whether it's checked out.
 *
 * You interact with event sourced actors by sending commands to them,
 * see classes implementing [[ShoppingCart.Command]].
 *
 * The command handler validates and translates commands to events, see classes implementing [[ShoppingCart.Event]].
 * It's the events that are persisted by the `EventSourcedBehavior`. The event handler updates the current
 * state based on the event. This is done when the event is first created, and when the entity is
 * loaded from the database - each event will be replayed to recreate the state
 * of the entity.
 */
public final class ShoppingCart extends EventSourcedBehaviorWithEnforcedReplies<ShoppingCart.Command, ShoppingCart.Event, ShoppingCart.State> {

    /**
     * The current state held by the `EventSourcedBehavior`.
     */
    // tag::state[]
    final static class State implements CborSerializable {
        final Map<String, Integer> items;
        private Optional<Instant> checkoutDate;

        public State() {
            this(new HashMap<>(), Optional.empty());
        }

        public State(Map<String, Integer> items, Optional<Instant> checkoutDate) {
            this.items = items;
            this.checkoutDate = checkoutDate;
        }

        public boolean isCheckedOut() {
            return checkoutDate.isPresent();
        }

        public boolean hasItem(String itemId) {
            return items.containsKey(itemId);
        }

        public State updateItem(String itemId, int quantity) {
            if (quantity == 0) {
                items.remove(itemId);
            } else {
                items.put(itemId, quantity);
            }
            return this;
        }

        public Summary toSummary() {
            return new Summary(items, isCheckedOut());
        }

        public int itemCount(String itemId) {
            return items.get(itemId);
        }

        public boolean isEmpty() {
            return items.isEmpty();
        }
    }
    // end::state[]

    /**
     * This interface defines all the commands (messages) that the ShoppingCart actor supports.
     */
    interface Command extends CborSerializable {}

    /**
     * A command to add an item to the cart.
     *
     * It replies with `StatusReply&lt;Summary&gt;`, which is sent back to the caller when
     * all the events emitted by this command are successfully persisted.
     */
    public static final class AddItem implements Command {
        final String itemId;
        final int quantity;
        final ActorRef<StatusReply<Summary>> replyTo;
        public AddItem(String itemId, int quantity, ActorRef<StatusReply<Summary>> replyTo) {
            this.itemId = itemId;
            this.quantity = quantity;
            this.replyTo = replyTo;
        }
    }

    /**
     * Summary of the shopping cart state, used in reply messages.
     */
    public static final class Summary implements CborSerializable {
        final Map<String, Integer> items;
        final boolean checkedOut;

        public Summary(Map<String, Integer> items, boolean checkedOut) {
            // defensive copy since items is a mutable object
            this.items = new HashMap<>(items);
            this.checkedOut = checkedOut;
        }
    }

    abstract static class Event implements CborSerializable {
        public final String cartId;
        public Event(String cartId) {
            this.cartId = cartId;
        }
    }

    abstract static class ItemEvent extends Event {
        public final String itemId;
        public ItemEvent(String cartId, String itemId) {
            super(cartId);
            this.itemId = itemId;
        }
    }

    final static class ItemAdded extends ItemEvent  {
        public final int quantity;
        public ItemAdded(String cartId, String itemId, int quantity) {
            super(cartId, itemId);
            this.quantity = quantity;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ItemAdded itemAdded = (ItemAdded) o;
            return quantity == itemAdded.quantity;
        }

        @Override
        public int hashCode() {
            return Objects.hash(quantity);
        }
    }


    final static EntityTypeKey<Command> ENTITY_KEY = EntityTypeKey.create(Command.class, "ShoppingCart");

    // tag::tagging[]
    final static List<String> TAGS = Collections.unmodifiableList(
            Arrays.asList("carts-0", "carts-1", "carts-2", "carts-3", "carts-4"));

    // tag::howto-write-side-without-role[]
    public static void init(ActorSystem<?> system) {
        ClusterSharding.get(system).init(Entity.of(ENTITY_KEY, entityContext -> {
            int i = Math.abs(entityContext.getEntityId().hashCode() % TAGS.size());
            String selectedTag = TAGS.get(i);
            return ShoppingCart.create(entityContext.getEntityId(), selectedTag);
        }));
    }
    // end::howto-write-side-without-role[]
    // end::tagging[]

    public static Behavior<Command> create(String cartId, String projectionTag) {
        return Behaviors.setup(ctx ->
            EventSourcedBehavior.start(new ShoppingCart(cartId, projectionTag), ctx)
        );
    }

    // tag::withTagger[]
    private final String projectionTag;

    private final String cartId;

    private ShoppingCart(String cartId, String projectionTag) {
        super(PersistenceId.of(ENTITY_KEY.name(), cartId),
                SupervisorStrategy.restartWithBackoff(Duration.ofMillis(200), Duration.ofSeconds(5), 0.1));
        this.cartId = cartId;
        this.projectionTag = projectionTag;
    }

    @Override
    public Set<String> tagsFor(Event event) {
        return Collections.singleton(projectionTag);
    }
    // end::withTagger[]

    @Override
    public RetentionCriteria retentionCriteria() {
        return RetentionCriteria.snapshotEvery(100, 3);
    }

    @Override
    public State emptyState() {
        return new State();
    }
    // tag::commandHandlers[]
    @Override
    public CommandHandlerWithReply<Command, Event, State> commandHandler() {
        // The shopping cart behavior changes if it's checked out or not.
        // The commands are handled differently for each case.
        CommandHandlerWithReplyBuilder<Command, Event, State> builder = newCommandHandlerWithReplyBuilder();
        // end::commandHandlers[]

        builder.forState(State::isCheckedOut)
            .onCommand(
                AddItem.class,
                cmd -> Effect().reply(cmd.replyTo, StatusReply.error("Can't add an item to an already checked out shopping cart")));

        // tag::commandHandlers[]
        builder.forState(state -> !state.isCheckedOut())
            .onCommand(AddItem.class, this::onAddItem);

        return builder.build();
    }
    // end::commandHandlers[]

    private ReplyEffect<Event, State> onAddItem(State state, AddItem cmd) {
        if (state.hasItem(cmd.itemId)) {
            return Effect().reply(
                    cmd.replyTo,
                    StatusReply.error("Item '" + cmd.itemId + "' was already added to this shopping cart"));
        } else if (cmd.quantity <= 0) {
            return Effect().reply(cmd.replyTo, StatusReply.error("Quantity must be greater than zero"));
        } else {
            return Effect().persist(new ItemAdded(cartId, cmd.itemId, cmd.quantity))
                .thenReply(cmd.replyTo, updatedCart -> StatusReply.success(updatedCart.toSummary()));
        }
    }


    @Override
    public EventHandler<State, Event> eventHandler() {
        return newEventHandlerBuilder().forAnyState()
            .onEvent(ItemAdded.class, (state, evt) -> state.updateItem(evt.itemId, evt.quantity))
            .build();
    }
}
