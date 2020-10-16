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

        public State removeItem(String itemId) {
            items.remove(itemId);
            return this;
        }

        public State checkout(Instant now) {
            checkoutDate = Optional.of(now);
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
     * A command to remove an item from the cart.
     */
    public static final class RemoveItem implements Command {
        final String itemId;
        final ActorRef<StatusReply<Summary>> replyTo;
        public RemoveItem(String itemId, ActorRef<StatusReply<Summary>> replyTo) {
            this.itemId = itemId;
            this.replyTo = replyTo;
        }
    }

    /**
     * A command to adjust the quantity of an item in the cart.
     */
    public static final class AdjustItemQuantity implements Command {
        final String itemId;
        final int quantity;
        final ActorRef<StatusReply<Summary>> replyTo;
        public AdjustItemQuantity(String itemId, int quantity, ActorRef<StatusReply<Summary>> replyTo) {
            this.itemId = itemId;
            this.quantity = quantity;
            this.replyTo = replyTo;
        }
    }

    /**
     * A command to checkout the shopping cart.
     */
    // tag::checkoutCommand[]
    public static final class Checkout implements Command {
        final ActorRef<StatusReply<Summary>> replyTo;
        @JsonCreator
        public Checkout(ActorRef<StatusReply<Summary>> replyTo) {
            this.replyTo = replyTo;
        }
    }
    // end::checkoutCommand[]

    /**
     * A command to get the current state of the shopping cart.
     */
    // tag::getCommand[]
    public static final class Get implements Command {
        final ActorRef<Summary> replyTo;
        @JsonCreator
        public Get(ActorRef<Summary> replyTo) {
            this.replyTo = replyTo;
        }
    }
    // end::getCommand[]

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

    final static class ItemRemoved extends ItemEvent  {
        public final int oldQuantity;
        public ItemRemoved(String cartId, String itemId, int oldQuantity) {
            super(cartId, itemId);
            this.oldQuantity = oldQuantity;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ItemRemoved that = (ItemRemoved) o;
            return oldQuantity == that.oldQuantity;
        }

        @Override
        public int hashCode() {
            return Objects.hash(oldQuantity);
        }
    }

    final static class ItemQuantityAdjusted extends ItemEvent  {
        final int oldQuantity;
        final int newQuantity;
        public ItemQuantityAdjusted(String cartId, String itemId, int oldQuantity, int newQuantity) {
            super(cartId, itemId);
            this.oldQuantity = oldQuantity;
            this.newQuantity = newQuantity;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ItemQuantityAdjusted that = (ItemQuantityAdjusted) o;
            return oldQuantity == that.oldQuantity &&
                    newQuantity == that.newQuantity;
        }

        @Override
        public int hashCode() {
            return Objects.hash(oldQuantity, newQuantity);
        }
    }

    // tag::checkedOutEvent[]
    final static class CheckedOut extends Event {
        final Instant eventTime;
        public CheckedOut(String cartId, Instant eventTime) {
            super(cartId);
            this.eventTime = eventTime;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            CheckedOut that = (CheckedOut) o;
            return Objects.equals(eventTime, that.eventTime);
        }

        @Override
        public int hashCode() {
            return Objects.hash(eventTime);
        }
    }
    // end::checkedOutEvent[]

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

        // tag::checkedOutShoppingCart[]
        builder.forState(State::isCheckedOut)
            // end::checkedOutShoppingCart[]
            .onCommand(Get.class, this::onGet)
            // tag::checkedOutShoppingCart[]
            .onCommand(
                AddItem.class,
                cmd -> Effect().reply(cmd.replyTo, StatusReply.error("Can't add an item to an already checked out shopping cart")))
            // end::checkedOutShoppingCart[]
            .onCommand(
                RemoveItem.class,
                cmd -> Effect().reply(cmd.replyTo, StatusReply.error("Can't remove an item from an already checked out shopping cart")))
            .onCommand(
                AdjustItemQuantity.class,
                cmd -> Effect().reply(cmd.replyTo, StatusReply.error("Can't adjust item on an already checked out shopping cart")))
            // tag::checkedOutShoppingCart[]
            .onCommand(
                Checkout.class,
                cmd -> Effect().reply(cmd.replyTo, StatusReply.error("Can't checkout already checked out shopping cart")));
        // end::checkedOutShoppingCart[]

        // tag::commandHandlers[]
        builder.forState(state -> !state.isCheckedOut())
            .onCommand(Get.class, this::onGet)
            .onCommand(AddItem.class, this::onAddItem)
            .onCommand(RemoveItem.class, this::onRemoveItem)
            .onCommand(AdjustItemQuantity.class, this::onAdjustItemQuantity)
            .onCommand(Checkout.class, this::onCheckout);

        return builder.build();
    }
    // end::commandHandlers[]

    // tag::getCommandHandler[]
    private ReplyEffect<Event, State> onGet(State state, Get cmd) {
        return Effect().reply(cmd.replyTo, state.toSummary());
    }
    // end::getCommandHandler[]
    // tag::commandHandlers[]

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

    private ReplyEffect<Event, State> onRemoveItem(State state, RemoveItem cmd) {
        if (state.hasItem(cmd.itemId)) {
            return Effect().persist(new ItemRemoved(cartId, cmd.itemId, state.itemCount(cmd.itemId)))
                    .thenReply(cmd.replyTo, updatedCart -> StatusReply.success(updatedCart.toSummary()));
        } else {
            return Effect().reply(cmd.replyTo, StatusReply.success(state.toSummary())); // removing an item is idempotent
        }
    }

    private ReplyEffect<Event, State> onAdjustItemQuantity(State state, AdjustItemQuantity cmd) {
        if (cmd.quantity <= 0) {
            return Effect().reply(cmd.replyTo, StatusReply.error("Quantity must be greater than zero"));
        } else if (state.hasItem(cmd.itemId)) {
            return Effect().persist(new ItemQuantityAdjusted(cartId, cmd.itemId, state.itemCount(cmd.itemId), cmd.quantity))
                    .thenReply(cmd.replyTo, updatedCart -> StatusReply.success(updatedCart.toSummary()));
        } else {
            return Effect().reply(
                    cmd.replyTo,
                    StatusReply.error("Cannot adjust quantity for item '" + cmd.itemId + "'. Item not present on cart"));
        }
    }

    private ReplyEffect<Event, State> onCheckout(State state, Checkout cmd) {
        if (state.isEmpty()) {
            return Effect().reply(cmd.replyTo, StatusReply.error("Cannot checkout an empty shopping cart"));
        } else {
            return Effect().persist(new CheckedOut(cartId, Instant.now()))
                    .thenReply(cmd.replyTo, updatedCart -> StatusReply.success(updatedCart.toSummary()));
        }
    }

    // tag::checkedOutEventHandler[]
    @Override
    public EventHandler<State, Event> eventHandler() {
        return newEventHandlerBuilder().forAnyState()
            .onEvent(ItemAdded.class, (state, evt) -> state.updateItem(evt.itemId, evt.quantity))
            .onEvent(ItemRemoved.class, (state, evt) -> state.removeItem(evt.itemId))
            .onEvent(ItemQuantityAdjusted.class, (state, evt) -> state.updateItem(evt.itemId, evt.newQuantity))
            .onEvent(CheckedOut.class, (state, evt) -> state.checkout(evt.eventTime))
            .build();
    }
    // end::checkedOutEventHandler[]
}
