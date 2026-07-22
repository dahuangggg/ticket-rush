package dev.dahuangggg.ticketrush.ai.config;

import dev.dahuangggg.ticketrush.ai.tools.EventQueryTools;
import dev.dahuangggg.ticketrush.ai.tools.OrderQueryTools;
import dev.langchain4j.agent.tool.Tool;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AiConfigTest {

    @Test
    void productionToolAllowlistContainsOnlyReadQueries() {
        EventQueryTools eventTools = new EventQueryTools(null, null);
        OrderQueryTools orderTools = new OrderQueryTools(null);

        List<Object> registeredTools = new AiConfig().aiReadOnlyTools(eventTools, orderTools).tools();

        assertEquals(List.of(EventQueryTools.class, OrderQueryTools.class),
                registeredTools.stream().map(Object::getClass).toList());
        assertEquals(Set.of("searchEvents", "getEventDetail", "listSkus", "getMyOrders"),
                registeredTools.stream()
                        .flatMap(tool -> List.of(tool.getClass().getDeclaredMethods()).stream())
                        .filter(method -> method.isAnnotationPresent(Tool.class))
                        .map(Method::getName)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet()));
    }
}
