package com.springboot.backend.recommendation.classification;

import com.springboot.backend.exception.ResourceNotFoundException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Every {@link CategorySpecMapping} bean, looked up by product category. */
@Component
public class CategorySpecRegistry {

    private final Map<String, CategorySpecMapping<?>> byCategory = new HashMap<>();

    public CategorySpecRegistry(List<CategorySpecMapping<?>> mappings) {
        for (CategorySpecMapping<?> mapping : mappings) {
            if (byCategory.put(key(mapping.category()), mapping) != null) {
                throw new IllegalStateException("Two spec mappings for category " + mapping.category());
            }
        }
    }

    /** Throws when the category has no mapping, because then it cannot be scored at all. */
    public CategorySpecMapping<?> forCategory(String category) {
        CategorySpecMapping<?> mapping = category == null ? null : byCategory.get(key(category));
        if (mapping == null) {
            throw new ResourceNotFoundException(
                    "No spec mapping for category " + category + ", so it cannot be compared");
        }
        return mapping;
    }

    public List<CategorySpecMapping<?>> all() {
        return List.copyOf(byCategory.values());
    }

    private static String key(String category) {
        return category.trim().toUpperCase(Locale.ROOT);
    }
}
