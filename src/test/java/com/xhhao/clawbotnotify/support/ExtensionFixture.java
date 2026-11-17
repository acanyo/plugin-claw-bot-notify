package com.xhhao.clawbotnotify.support;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.xhhao.clawbotnotify.model.CommentLink;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.dao.OptimisticLockingFailureException;
import reactor.core.publisher.Mono;
import run.halo.app.extension.Extension;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ListResult;
import run.halo.app.extension.PageRequest;
import run.halo.app.extension.ReactiveExtensionClient;

/** Serialized snapshots and compare-and-swap versions model persistence across worker instances. */
public final class ExtensionFixture {
    public final ReactiveExtensionClient client = mock(ReactiveExtensionClient.class);
    public final AtomicInteger updates = new AtomicInteger();
    public volatile boolean failUpdates;
    public volatile Class<?> failCreatesFor;
    private final Map<String, Extension> rows = new ConcurrentHashMap<>();
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());

    public ExtensionFixture() {
        doAnswer(call -> Mono.defer(() -> {
                    Extension found = get(call.getArgument(0), call.getArgument(1));
                    return Mono.justOrEmpty(found);
                }))
                .when(client)
                .fetch(any(Class.class), anyString());
        doAnswer(call -> Mono.defer(() -> {
                    Extension found = get(call.getArgument(0), call.getArgument(1));
                    return Mono.justOrEmpty(found).switchIfEmpty(Mono.error(new IllegalStateException("NOT_FOUND")));
                }))
                .when(client)
                .get(any(), anyString());
        doAnswer(call -> Mono.fromCallable(() -> put(call.getArgument(0), true)))
                .when(client)
                .create(any());
        doAnswer(call -> Mono.fromCallable(() -> put(call.getArgument(0), false)))
                .when(client)
                .update(any());
        doAnswer(call -> Mono.fromCallable(() -> {
                    Extension ext = call.getArgument(0);
                    rows.remove(key(ext.getClass(), ext.getMetadata().getName()));
                    return ext;
                }))
                .when(client)
                .delete(any());
        doAnswer(call -> Mono.fromCallable(() -> {
                    Class<?> type = call.getArgument(0);
                    ListOptions options = call.getArgument(1);
                    PageRequest page = call.getArgument(2);
                    var result = new ArrayList<Extension>();
                    for (var ext : rows.values()) {
                        if (!type.isInstance(ext)) continue;
                        if (options.getFieldSelector() != null
                                && !matches(options.getFieldSelector().query(), ext)) continue;
                        result.add(copy(ext));
                    }
                    Comparator<Extension> order = (a, b) -> 0;
                    for (var item : page.getSort()) {
                        Comparator<Extension> next = Comparator.comparing(
                                e -> (Comparable) property(e, item.getProperty()),
                                Comparator.nullsFirst(Comparator.naturalOrder()));
                        order = order.thenComparing(item.isAscending() ? next : next.reversed());
                    }
                    result.sort(order);
                    int from = Math.min(result.size(), Math.max(0, (page.getPageNumber() - 1) * page.getPageSize()));
                    int to = Math.min(result.size(), from + page.getPageSize());
                    return new ListResult<>(
                            page.getPageNumber(), page.getPageSize(), result.size(), result.subList(from, to));
                }))
                .when(client)
                .listBy(any(), any(), any());
    }

    public synchronized <T extends Extension> T put(T value, boolean create) {
        if (create && failCreatesFor != null && failCreatesFor.isInstance(value))
            throw new IllegalStateException("SIMULATED_CREATE_FAILURE");
        if (!create && failUpdates) throw new IllegalStateException("SIMULATED_STORAGE_FAILURE");
        String key = key(value.getClass(), value.getMetadata().getName());
        Extension previous = rows.get(key);
        if (create && previous != null) throw new OptimisticLockingFailureException("DUPLICATE");
        if (!create
                && (previous == null
                        || !previous.getMetadata()
                                .getVersion()
                                .equals(value.getMetadata().getVersion()))) {
            throw new OptimisticLockingFailureException("STALE_VERSION");
        }
        if (value instanceof CommentLink link
                && rows.values().stream()
                        .anyMatch(ext -> ext instanceof CommentLink other
                                && !ext.getMetadata()
                                        .getName()
                                        .equals(value.getMetadata().getName())
                                && other.getSpec()
                                        .getScopeCode()
                                        .equals(link.getSpec().getScopeCode())))
            throw new OptimisticLockingFailureException("UNIQUE_CODE");
        T saved = copy(value);
        saved.getMetadata()
                .setVersion(previous == null ? 1L : previous.getMetadata().getVersion() + 1);
        if (saved.getMetadata().getCreationTimestamp() == null)
            saved.getMetadata().setCreationTimestamp(Instant.now());
        rows.put(key, saved);
        updates.incrementAndGet();
        return copy(saved);
    }

    public synchronized <T extends Extension> T get(Class<T> type, String name) {
        var ext = rows.get(key(type, name));
        return ext == null ? null : type.cast(copy(ext));
    }

    @SuppressWarnings("unchecked")
    private <T extends Extension> T copy(T value) {
        try {
            return (T) json.readValue(json.writeValueAsBytes(value), value.getClass());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Object property(Extension extension, String name) {
        return new BeanWrapperImpl(extension).getPropertyValue(name);
    }

    private static Object member(Object query, String name) {
        try {
            var method = query.getClass().getDeclaredMethod(name);
            method.setAccessible(true);
            return method.invoke(query);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean matches(Object query, Extension extension) {
        String type = query.getClass().getSimpleName();
        if (type.equals("AndCondition"))
            return matches(member(query, "left"), extension) && matches(member(query, "right"), extension);
        if (type.equals("OrCondition"))
            return matches(member(query, "left"), extension) || matches(member(query, "right"), extension);
        if (type.equals("EmptyCondition")) return true;
        Object value = property(extension, (String) member(query, "indexName"));
        if (value instanceof Enum<?> e) value = e.name();
        if (type.equals("InCondition")) return ((java.util.Collection<?>) member(query, "keys")).contains(value);
        if (type.equals("StringStartsWithCondition"))
            return value instanceof String text && text.startsWith((String) member(query, "prefix"));
        if (type.equals("IsNullCondition")) return value == null;
        if (type.equals("EqualCondition")) return Objects.equals(value, member(query, "key"));
        if (type.equals("NotEqualCondition")) return !Objects.equals(value, member(query, "key"));
        if (value == null) return false;
        int direction = ((Comparable) value)
                .compareTo(member(query, type.equals("GreaterThanCondition") ? "lowerBound" : "upperBound"));
        return type.equals("GreaterThanCondition")
                ? direction > 0 || direction == 0 && (boolean) member(query, "inclusive")
                : direction < 0 || direction == 0 && (boolean) member(query, "inclusive");
    }

    private static String key(Class<?> type, String name) {
        return type.getName() + ":" + name;
    }
}
