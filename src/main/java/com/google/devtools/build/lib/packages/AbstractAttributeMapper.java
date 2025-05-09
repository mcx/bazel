// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.packages;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.packages.BuildType.SelectorList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javax.annotation.Nullable;

/**
 * Base {@link AttributeMap} implementation providing direct, unmanipulated access to
 * underlying attribute data as stored within the Rule.
 *
 * <p>Any instantiable subclass should define a clear policy of what it does with this
 * data before exposing it to consumers.
 */
public abstract class AbstractAttributeMapper implements AttributeMap {
  final AttributeProvider ruleClass;
  final RuleOrMacroInstance rule;
  private final Label ruleLabel;

  protected AbstractAttributeMapper(RuleOrMacroInstance rule) {
    this.ruleClass = rule.getAttributeProvider();
    this.ruleLabel = rule.getLabel();
    this.rule = rule;
  }

  @Override
  public String describeRule() {
    return String.format("%s %s", this.rule.getAttributeProvider(), getLabel());
  }

  @Override
  public Label getLabel() {
    return ruleLabel;
  }

  @Nullable
  @Override
  public <T> T get(String attributeName, Type<T> type) {
    return getFromRawAttributeValue(rule.getAttr(attributeName, type), attributeName, type);
  }

  @SuppressWarnings("unchecked")
  final <T> T getFromRawAttributeValue(Object value, String attributeName, Type<T> type) {
    if (value instanceof Attribute.ComputedDefault) {
      value = ((Attribute.ComputedDefault) value).getDefault(this);
    } else if (value instanceof Attribute.LateBoundDefault) {
      value = ((Attribute.LateBoundDefault<?, ?>) value).getDefault(rule);
    } else if (value instanceof MaterializingDefault) {
      value = ((MaterializingDefault<?, ?>) value).getDefault();
    } else if (value instanceof SelectorList) {
      throw new IllegalArgumentException(
          String.format(
              "Unexpected configurable attribute \"%s\" in %s rule %s: expected %s, is %s",
              attributeName, ruleClass, ruleLabel, type, value));
    }

    // Hot code path - avoid the overhead of calling type.cast(value). The rule would have already
    // failed on construction if one of its attributes was of the wrong type (including computed
    // defaults).
    return (T) value;
  }

  /**
   * Returns the given attribute if it's a computed default, null otherwise.
   *
   * @throws IllegalArgumentException if the given attribute doesn't exist with the specified
   *         type. This happens whether or not it's a computed default.
   */
  @VisibleForTesting // Should be protected
  @Nullable
  public <T> Attribute.ComputedDefault getComputedDefault(String attributeName, Type<T> type) {
    Object value = rule.getAttr(attributeName, type);
    if (value instanceof Attribute.ComputedDefault computedDefault) {
      return computedDefault;
    } else {
      return null;
    }
  }

  /**
   * Returns the given attribute if it's a {@link Attribute.LateBoundDefault}, null otherwise.
   *
   * @throws IllegalArgumentException if the given attribute doesn't exist with the specified
   *         type. This happens whether or not it's a late bound default.
   */
  @Nullable
  @SuppressWarnings("unchecked")
  public <T> Attribute.LateBoundDefault<?, T> getLateBoundDefault(
      String attributeName, Type<T> type) {
    Object value = rule.getAttr(attributeName, type);
    if (value instanceof Attribute.LateBoundDefault) {
      return (Attribute.LateBoundDefault<?, T>) value;
    } else {
      return null;
    }
  }

  @Nullable
  @SuppressWarnings("unchecked")
  public <T> MaterializingDefault<?, T> getMaterializer(String attributeName, Type<T> type) {
    Object value = rule.getAttr(attributeName, type);
    if (value instanceof MaterializingDefault<?, ?>) {
      return (MaterializingDefault<?, T>) value;
    } else {
      return null;
    }
  }

  @Override
  public Iterable<String> getAttributeNames() {
    return Lists.transform(ruleClass.getAttributes(), Attribute::getName);
  }

  @Nullable
  @Override
  public Type<?> getAttributeType(String attrName) {
    Attribute attr = getAttributeDefinition(attrName);
    return attr == null ? null : attr.getType();
  }

  @Nullable
  @Override
  public Attribute getAttributeDefinition(String attrName) {
    return ruleClass.getAttributeByNameMaybe(attrName);
  }

  @Override
  public boolean isAttributeValueExplicitlySpecified(String attributeName) {
    return rule.isAttributeValueExplicitlySpecified(attributeName);
  }

  @Override
  public PackageArgs getPackageArgs() {
    return rule.getPackageArgs();
  }

  @Override
  public final void visitAllLabels(BiConsumer<Attribute, Label> consumer) {
    visitLabels(DependencyFilter.ALL_DEPS, consumer);
  }

  @Override
  public final void visitLabels(String attributeName, Consumer<Label> consumer) {
    visitLabels(
        ImmutableList.of(ruleClass.getAttributeByName(attributeName)),
        DependencyFilter.ALL_DEPS,
        (attr, label) -> consumer.accept(label));
  }

  @Override
  public void visitLabels(DependencyFilter filter, BiConsumer<Attribute, Label> consumer) {
    visitLabels(ruleClass.getAttributes(), filter, consumer);
  }

  private void visitLabels(
      List<Attribute> attributes, DependencyFilter filter, BiConsumer<Attribute, Label> consumer) {
    Type.LabelVisitor visitor =
        (label, attribute) -> {
          if (label != null) {
            consumer.accept(attribute, label);
          }
        };
    for (Attribute attribute : attributes) {
      Type<?> type = attribute.getType();
      // TODO(bazel-team): clean up the typing / visitation interface so we don't have to
      // special-case these types.
      if (type != BuildType.OUTPUT
          && type != BuildType.OUTPUT_LIST
          && type != BuildType.NODEP_LABEL
          && type != BuildType.NODEP_LABEL_LIST
          && filter.test(rule, attribute)) {
        visitLabels(attribute, type, visitor);
      }
    }
  }

  /** Visits all labels reachable from the given attribute. */
  <T> void visitLabels(Attribute attribute, Type<T> type, Type.LabelVisitor visitor) {
    T value = get(attribute.getName(), type);
    if (value != null) { // null values are particularly possible for computed defaults.
      type.visitLabels(visitor, value, attribute);
    }
  }

  @Override
  public final boolean isConfigurable(String attributeName) {
    return isConfigurable(rule, attributeName);
  }

  /**
   * Check if an attribute is configurable (uses select) or, if it's a computed default, if any of
   * its inputs are configurable.
   */
  public static boolean isConfigurable(RuleOrMacroInstance rule, String attributeName) {
    return isConfigurable(rule, attributeName, /* includeComputedDefaults= */ true);
  }

  /**
   * Checks if an attribute is uses select. If {@code includeComputedDefaults} is true, also returns
   * true on computed defaults that have any configurable inputs.
   */
  public static boolean isConfigurable(
      RuleOrMacroInstance rule, String attributeName, boolean includeComputedDefaults) {
    Object attr = rule.getAttr(attributeName);
    if (attr instanceof Attribute.ComputedDefault computedDefault) {
      if (!includeComputedDefaults) {
        return false;
      }
      for (String dep : computedDefault.dependencies()) {
        if (isConfigurable(rule, dep)) {
          return true;
        }
      }
      return false;
    }
    Attribute attrDef = rule.getAttributeProvider().getAttributeByNameMaybe(attributeName);
    return attrDef != null && rule.getSelectorList(attributeName, attrDef.getType()) != null;
  }

  /**
   * Returns a {@link SelectorList} for the given attribute if the attribute is configurable
   * for this rule, null otherwise.
   *
   * @return a {@link SelectorList} if the attribute takes the form
   *     "attrName = { 'a': value1_of_type_T, 'b': value2_of_type_T }") for this rule, null
   *     if it takes the form "attrName = value_of_type_T", null if it doesn't exist
   * @throws IllegalArgumentException if the attribute is configurable but of the wrong type
   */
  @Nullable
  public final <T> SelectorList<T> getSelectorList(String attributeName, Type<T> type) {
    return rule.getSelectorList(attributeName, type);
  }

  /**
   * Helper routine that just checks the given attribute has the given type for this rule and throws
   * an IllegalException if not.
   */
  void checkType(String attrName, Type<?> type) {
    Integer index = ruleClass.getAttributeIndex(attrName);
    if (index == null) {
      throw new IllegalArgumentException(
          "No such attribute " + attrName + " in " + ruleClass + " rule " + ruleLabel);
    }
    Attribute attr = ruleClass.getAttribute(index);
    if (attr.getType() != type) {
      throw new IllegalArgumentException(
          "Attribute " + attrName + " is of type " + attr.getType() + " and not of type " + type
              + " in " + ruleClass + " rule " + ruleLabel);
    }
  }


  @Override
  public boolean has(String attrName) {
    Attribute attribute = ruleClass.getAttributeByNameMaybe(attrName);
    return attribute != null;
  }

  @Override
  public <T> boolean has(String attrName, Type<T> type) {
    return getAttributeType(attrName) == type;
  }
}
