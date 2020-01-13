/*
 * GRAKN.AI - THE KNOWLEDGE GRAPH
 * Copyright (C) 2019 Grakn Labs Ltd
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package grakn.core.graph.diskstorage.configuration;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import org.apache.commons.lang.StringUtils;

import java.util.List;
import java.util.Objects;

public abstract class ConfigElement {

    private static final char SEPARATOR = '.';
    private static final char[] ILLEGAL_CHARS = new char[]{SEPARATOR, ' ', '\t', '#', '@', '<', '>', '?', '/', ';', '"', '\'', ':', '+', '(', ')', '*', '^', '`', '~', '$', '%', '|', '\\', '{', '[', ']', '}'};

    private final ConfigNamespace namespace;
    private final String name;
    private final String description;

    public ConfigElement(ConfigNamespace namespace, String name, String description) {
        Preconditions.checkArgument(StringUtils.isNotBlank(name), "Name cannot be empty: %s", name);
        Preconditions.checkArgument(!StringUtils.containsAny(name, ILLEGAL_CHARS), "Name contains illegal character: %s (%s)", name, ILLEGAL_CHARS);
        Preconditions.checkArgument(namespace != null || this instanceof ConfigNamespace, "Need to specify namespace for ConfigOption");
        Preconditions.checkArgument(StringUtils.isNotBlank(description));
        this.namespace = namespace;
        this.name = name;
        this.description = description;
        if (namespace != null) namespace.registerChild(this);
    }

    public ConfigNamespace getNamespace() {
        Preconditions.checkArgument(namespace != null, "Cannot get namespace of root");
        return namespace;
    }

    public boolean isRoot() {
        return namespace == null;
    }

    public ConfigNamespace getRoot() {
        if (isRoot()) return (ConfigNamespace) this;
        else return getNamespace().getRoot();
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public abstract boolean isOption();

    public boolean isNamespace() {
        return !isOption();
    }

    @Override
    public String toString() {
        return (namespace != null ? namespace.toString() + SEPARATOR : "") + name;
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, namespace);
    }

    @Override
    public boolean equals(Object oth) {
        if (this == oth) {
            return true;
        } else if (!getClass().isInstance(oth)) {
            return false;
        }
        ConfigElement c = (ConfigElement) oth;
        return name.equals(c.name) && namespace == c.namespace;
    }

    public static String[] getComponents(String path) {
        return StringUtils.split(path, SEPARATOR);
    }

    public static String toString(ConfigElement element) {
        String result = element.getName();
        if (element.isNamespace()) {
            result = "+ " + result;
            if (((ConfigNamespace) element).isUmbrella()) {
                result += " [*]";
            }
        } else {
            result = "- " + result;
            ConfigOption option = (ConfigOption) element;
            result += " [";
            switch (option.getType()) {
                case FIXED:
                    result += "f";
                    break;
                case GLOBAL_OFFLINE:
                    result += "g!";
                    break;
                case GLOBAL:
                    result += "g";
                    break;
                case MASKABLE:
                    result += "m";
                    break;
                case LOCAL:
                    result += "l";
                    break;
            }
            result += "," + option.getDatatype().getSimpleName();
            result += "," + option.getDefaultValue();
            result += "]";
        }
        result = result + "\n";
        String desc = element.getDescription();
        result += "\t" + '"' + desc.substring(0, Math.min(desc.length(), 50)) + '"';
        return result;
    }

    public static String getPath(ConfigElement element, String... umbrellaElements) {
        return getPath(element, false, umbrellaElements);
    }

    public static String getPath(ConfigElement element, boolean includeRoot, String... umbrellaElements) {
        Preconditions.checkNotNull(element);
        if (umbrellaElements == null) umbrellaElements = new String[0];
        StringBuilder path = new StringBuilder(element.getName());
        int umbrellaPos = umbrellaElements.length - 1;
        while (!element.isRoot() && !element.getNamespace().isRoot()) {
            ConfigNamespace parent = element.getNamespace();
            if (parent.isUmbrella()) {
                Preconditions.checkArgument(umbrellaPos >= 0, "Missing umbrella element path for element: %s", element);
                String umbrellaName = umbrellaElements[umbrellaPos];
                Preconditions.checkArgument(!StringUtils.containsAny(umbrellaName, ILLEGAL_CHARS), "Invalid umbrella name provided: %s. Contains illegal chars", umbrellaName);
                path.insert(0, umbrellaName + SEPARATOR);
                umbrellaPos--;
            }
            path.insert(0, parent.getName() + SEPARATOR);
            element = parent;
        }
        if (includeRoot) {
            // Assumes that roots are not umbrellas
            // If roots could be umbrellas, we might have to change the interpretation of umbrellaElements
            path.insert(0, (element.isRoot() ?
                    element.getName() :
                    element.getNamespace().getName()) + SEPARATOR);
        }
        //Don't make this check so that we can still access more general config items
        Preconditions.checkArgument(umbrellaPos < 0, "Found unused umbrella element: %s", umbrellaPos < 0 ? null : umbrellaElements[umbrellaPos]);
        return path.toString();
    }

    public static PathIdentifier parse(ConfigNamespace root, String path) {
        if (StringUtils.isBlank(path)) return new PathIdentifier(root, new String[]{}, false);
        String[] components = getComponents(path);
        Preconditions.checkArgument(components.length > 0, "Empty path provided: %s", path);
        List<String> umbrellaElements = Lists.newArrayList();
        ConfigNamespace parent = root;
        ConfigElement last = root;
        boolean lastIsUmbrella = false;
        for (int i = 0; i < components.length; i++) {
            if (parent.isUmbrella() && !lastIsUmbrella) {
                umbrellaElements.add(components[i]);
                lastIsUmbrella = true;
            } else {
                last = parent.getChild(components[i]);
                Preconditions.checkArgument(last != null, "Unknown configuration element in namespace [%s]: %s", parent.toString(), components[i]);

                if (i + 1 < components.length) {
                    Preconditions.checkArgument(last instanceof ConfigNamespace, "Expected namespace at position [%s] of [%s] but got: %s", i, path, last);
                    parent = (ConfigNamespace) last;
                }
                lastIsUmbrella = false;
            }
        }
        return new PathIdentifier(last, umbrellaElements.toArray(new String[umbrellaElements.size()]), lastIsUmbrella);
    }

    public static class PathIdentifier {

        public final ConfigElement element;
        public final String[] umbrellaElements;
        public final boolean lastIsUmbrella;

        private PathIdentifier(ConfigElement element, String[] umbrellaElements, boolean lastIsUmbrella) {
            this.lastIsUmbrella = lastIsUmbrella;
            Preconditions.checkNotNull(element);
            Preconditions.checkNotNull(umbrellaElements);
            this.element = element;
            this.umbrellaElements = umbrellaElements;
        }
    }


}