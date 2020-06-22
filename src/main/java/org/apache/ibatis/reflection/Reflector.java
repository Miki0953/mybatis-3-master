/**
 *    Copyright 2009-2020 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.reflection;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.ReflectPermission;
import java.lang.reflect.Type;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.ibatis.reflection.invoker.AmbiguousMethodInvoker;
import org.apache.ibatis.reflection.invoker.GetFieldInvoker;
import org.apache.ibatis.reflection.invoker.Invoker;
import org.apache.ibatis.reflection.invoker.MethodInvoker;
import org.apache.ibatis.reflection.invoker.SetFieldInvoker;
import org.apache.ibatis.reflection.property.PropertyNamer;

/**
 * This class represents a cached set of class definition information that
 * allows for easy mapping between property names and getter/setter methods.
 * 反射器
 * 先补充一下Class类的知识：Class类，该类的实例表示正在运行的Java应用程序中的类和接口。
 * @author Clinton Begin
 */
public class Reflector {
  // 对应的Class类型
  private final Class<?> type;
  /**
   * 声明一下JavaBean规范：类中定义的实例变量也称为“字段”，属性则是通过getter/setter方法得到的
   * 属性只与类中的方法有关，跟是否存在对应的成员变量没有关系
   */
  // 可读属性的名称的集合，可读属性就是存在相应的getter方法，初始值为空数组
  private final String[] readablePropertyNames;
  // 可写属性的名称的集合，可读属性就是存在相应的setter方法，初始值为空数组
  private final String[] writablePropertyNames;
  // 属性相应的setter方法，key是属性名称，value是Invoker，它是对setter方法对应Method对象的封装
  private final Map<String, Invoker> setMethods = new HashMap<>();
  // 属性相应的getter方法，key是属性名称，value是Invoker，它是对getter方法对应Method对象的封装
  private final Map<String, Invoker> getMethods = new HashMap<>();
  // 属性相应的setter方法的参数值类型，key是属性名称，value是setter方法的参数类型
  private final Map<String, Class<?>> setTypes = new HashMap<>();
  // 属性相应的getter方法的返回值类型，key是属性名称，value是getter方法的返回值类型
  private final Map<String, Class<?>> getTypes = new HashMap<>();
  // 记录存储默认的构造函数
  private Constructor<?> defaultConstructor;

  // 所有属性名称的集合
  private Map<String, String> caseInsensitivePropertyMap = new HashMap<>();

  // Reflector的构造函数，会解析指定的Class对象，并初始化caseInsensitivePropertyMap
  public Reflector(Class<?> clazz) {
    type = clazz;
    // 查找clazz的默认构造函数(无参构造函数)
    addDefaultConstructor(clazz);
    // 处理clazz中的getter方法，
    addGetMethods(clazz);
    // 处理clazz中的getter方法，
    addSetMethods(clazz);
    // 处理clazz中的字段
    addFields(clazz);
    readablePropertyNames = getMethods.keySet().toArray(new String[0]);
    writablePropertyNames = setMethods.keySet().toArray(new String[0]);
    for (String propName : readablePropertyNames) {
      caseInsensitivePropertyMap.put(propName.toUpperCase(Locale.ENGLISH), propName);
    }
    for (String propName : writablePropertyNames) {
      caseInsensitivePropertyMap.put(propName.toUpperCase(Locale.ENGLISH), propName);
    }
  }

  /**
   * 查找clazz的默认构造函数(无参构造函数)
   * @param clazz
   */
  private void addDefaultConstructor(Class<?> clazz) {
    // 获取所有的构造方法
    Constructor<?>[] constructors = clazz.getDeclaredConstructors();
    // 查询无参构造方法，如果这样的构造方法存在，就赋值给defaultConstructor
    Arrays.stream(constructors).filter(constructor -> constructor.getParameterTypes().length == 0)
      .findAny().ifPresent(constructor -> this.defaultConstructor = constructor);
  }

  /**
   * 处理clazz中的get方法
   * @param clazz
   */
  private void addGetMethods(Class<?> clazz) {
    // 包含有冲突方法的所有方法的Map集合，key为属性名，value为属性对应的方法
    Map<String, List<Method>> conflictingGetters = new HashMap<>();
    // 获取当前类及父类所有的成员方法
    Method[] methods = getClassMethods(clazz);
    // 查询无参的 且 名称是getXXX或isXXX的方法
    Arrays.stream(methods).filter(m -> m.getParameterTypes().length == 0 && PropertyNamer.isGetter(m.getName()))
      .forEach(m -> addMethodConflict(conflictingGetters, PropertyNamer.methodToProperty(m.getName()), m));
    // 去掉混淆的getter方法，并填充get方法的getMethods和getTypes map集合
    resolveGetterConflicts(conflictingGetters);
  }

  /**
   * 假如有一个类A和一个子类SubA，A类中定义了getName()方法，其返回值类型是List<String>而在其子类SubA中,
   * 重载了其getNames()方法且将返回值修改成ArrayList<String>类型，这种重载在Java中是合法的，但是在addMethodConflict方法中，
   * 程序会认为这是两个不同的方法，但是这显然不是我们所期望的。
   * 然后这个方法就是处理这种情况的，将混淆的那种方法去掉
   * @param conflictingGetters
   */
  private void resolveGetterConflicts(Map<String, List<Method>> conflictingGetters) {
    // 遍历map,每次处理一个属性的的所有方法
    for (Entry<String, List<Method>> entry : conflictingGetters.entrySet()) {
      // 当选的方法
      Method winner = null;
      // 属性名
      String propName = entry.getKey();
      // 是否是混淆方法标志
      boolean isAmbiguous = false;
      // 遍历该属性的所有方法(List)
      for (Method candidate : entry.getValue()) {
        // 如果当选方法为空，则候选方法当选称为当选方法
        if (winner == null) {
          winner = candidate;
          continue;
        }
        Class<?> winnerType = winner.getReturnType();
        Class<?> candidateType = candidate.getReturnType();
        // 当选和候选的方法 返回类型相同
        if (candidateType.equals(winnerType)) {
          // 如果返回类型不是布尔类型
          if (!boolean.class.equals(candidateType)) {
            isAmbiguous = true;
            break;
          } else if (candidate.getName().startsWith("is")) {
            winner = candidate;
          }
        } else if (candidateType.isAssignableFrom(winnerType)) {
          // isAssignableFrom方法是判断当前Class对象表示的类是否跟传入的参数的表示的类相同，或者是否是它的父类
          // OK getter type is descendant
          // 当选方法返回类型是候选方法返回类型的父类，则不作处理
        } else if (winnerType.isAssignableFrom(candidateType)) {
          // 候选方法返回类型是当选方法返回类型的父类，则需要替换：将候选方法提名当选
          winner = candidate;
        } else {
          isAmbiguous = true;
          break;
        }
      }
      // 填充get方法的getMethods和getTypes map集合
      addGetMethod(propName, winner, isAmbiguous);
    }
  }

  /**
   * 填充get方法的getMethods和getTypes map集合
   * @param name
   * @param method
   * @param isAmbiguous
   */
  private void addGetMethod(String name, Method method, boolean isAmbiguous) {
    MethodInvoker invoker = isAmbiguous
        ? new AmbiguousMethodInvoker(method, MessageFormat.format(
            "Illegal overloaded getter method with ambiguous type for property ''{0}'' in class ''{1}''. This breaks the JavaBeans specification and can cause unpredictable results.",
            name, method.getDeclaringClass().getName()))
        : new MethodInvoker(method);
    getMethods.put(name, invoker);
    Type returnType = TypeParameterResolver.resolveReturnType(method, type);
    getTypes.put(name, typeToClass(returnType));
  }

  /**
   * 处理clazz中的setter方法，
   * @param clazz
   */
  private void addSetMethods(Class<?> clazz) {
    Map<String, List<Method>> conflictingSetters = new HashMap<>();
    Method[] methods = getClassMethods(clazz);
    Arrays.stream(methods).filter(m -> m.getParameterTypes().length == 1 && PropertyNamer.isSetter(m.getName()))
      .forEach(m -> addMethodConflict(conflictingSetters, PropertyNamer.methodToProperty(m.getName()), m));
    resolveSetterConflicts(conflictingSetters);
  }

  /**
   * 处理同一属性名的方法集合，最后的结果是同一属性的方法都在Map对应的value中
   * @param conflictingMethods
   * @param name 属性名称
   * @param method 方法实例
   */
  private void addMethodConflict(Map<String, List<Method>> conflictingMethods, String name, Method method) {
    // 校验是不是有效的属性名
    if (isValidPropertyName(name)) {
      // 判断传入的属性名是否已经有对应的方法List<Method>，如果没有就创建一个ArrayList
      List<Method> list = conflictingMethods.computeIfAbsent(name, k -> new ArrayList<>());
      // 将方法添加到list
      list.add(method);
    }
  }

  private void resolveSetterConflicts(Map<String, List<Method>> conflictingSetters) {
    for (Entry<String, List<Method>> entry : conflictingSetters.entrySet()) {
      String propName = entry.getKey();
      List<Method> setters = entry.getValue();
      Class<?> getterType = getTypes.get(propName);
      boolean isGetterAmbiguous = getMethods.get(propName) instanceof AmbiguousMethodInvoker;
      boolean isSetterAmbiguous = false;
      Method match = null;
      for (Method setter : setters) {
        if (!isGetterAmbiguous && setter.getParameterTypes()[0].equals(getterType)) {
          // should be the best match
          match = setter;
          break;
        }
        if (!isSetterAmbiguous) {
          match = pickBetterSetter(match, setter, propName);
          isSetterAmbiguous = match == null;
        }
      }
      if (match != null) {
        addSetMethod(propName, match);
      }
    }
  }

  private Method pickBetterSetter(Method setter1, Method setter2, String property) {
    if (setter1 == null) {
      return setter2;
    }
    Class<?> paramType1 = setter1.getParameterTypes()[0];
    Class<?> paramType2 = setter2.getParameterTypes()[0];
    if (paramType1.isAssignableFrom(paramType2)) {
      return setter2;
    } else if (paramType2.isAssignableFrom(paramType1)) {
      return setter1;
    }
    MethodInvoker invoker = new AmbiguousMethodInvoker(setter1,
        MessageFormat.format(
            "Ambiguous setters defined for property ''{0}'' in class ''{1}'' with types ''{2}'' and ''{3}''.",
            property, setter2.getDeclaringClass().getName(), paramType1.getName(), paramType2.getName()));
    setMethods.put(property, invoker);
    Type[] paramTypes = TypeParameterResolver.resolveParamTypes(setter1, type);
    setTypes.put(property, typeToClass(paramTypes[0]));
    return null;
  }

  private void addSetMethod(String name, Method method) {
    MethodInvoker invoker = new MethodInvoker(method);
    setMethods.put(name, invoker);
    Type[] paramTypes = TypeParameterResolver.resolveParamTypes(method, type);
    setTypes.put(name, typeToClass(paramTypes[0]));
  }

  private Class<?> typeToClass(Type src) {
    Class<?> result = null;
    if (src instanceof Class) {
      result = (Class<?>) src;
    } else if (src instanceof ParameterizedType) {
      result = (Class<?>) ((ParameterizedType) src).getRawType();
    } else if (src instanceof GenericArrayType) {
      Type componentType = ((GenericArrayType) src).getGenericComponentType();
      if (componentType instanceof Class) {
        result = Array.newInstance((Class<?>) componentType, 0).getClass();
      } else {
        Class<?> componentClass = typeToClass(componentType);
        result = Array.newInstance(componentClass, 0).getClass();
      }
    }
    if (result == null) {
      result = Object.class;
    }
    return result;
  }

  /**
   * 处理class对象中所有字段，并填充setTypes、setMethods、getTypes、getMethods集合
   * @param clazz
   */
  private void addFields(Class<?> clazz) {
    // 获取clazz对象表示的类中所有声明的字段
    Field[] fields = clazz.getDeclaredFields();
    for (Field field : fields) {
      // setMethods集合中的属性名没有包含有当前的字段名
      if (!setMethods.containsKey(field.getName())) {
        // issue #379 - removed the check for final because JDK 1.5 allows
        // modification of final fields through reflection (JSR-133). (JGB)
        // pr #16 - final static can only be set by the classloader
        // 修饰符
        int modifiers = field.getModifiers();
        // 不是常量、但是静态变量的需要添加到setType集合
        if (!(Modifier.isFinal(modifiers) && Modifier.isStatic(modifiers))) {
          addSetField(field);
        }
      }
      if (!getMethods.containsKey(field.getName())) {
        addGetField(field);
      }
    }

    if (clazz.getSuperclass() != null) {
      addFields(clazz.getSuperclass());
    }
  }

  private void addSetField(Field field) {
    if (isValidPropertyName(field.getName())) {
      setMethods.put(field.getName(), new SetFieldInvoker(field));
      Type fieldType = TypeParameterResolver.resolveFieldType(field, type);
      setTypes.put(field.getName(), typeToClass(fieldType));
    }
  }

  private void addGetField(Field field) {
    if (isValidPropertyName(field.getName())) {
      getMethods.put(field.getName(), new GetFieldInvoker(field));
      Type fieldType = TypeParameterResolver.resolveFieldType(field, type);
      getTypes.put(field.getName(), typeToClass(fieldType));
    }
  }

  /**
   * 校验是否是属性名称
   * @param name
   * @return
   */
  private boolean isValidPropertyName(String name) {
    return !(name.startsWith("$") || "serialVersionUID".equals(name) || "class".equals(name));
  }

  /**
   * This method returns an array containing all methods
   * declared in this class and any superclass.
   * We use this method, instead of the simpler <code>Class.getMethods()</code>,
   * because we want to look for private methods as well.
   *
   * 返回一个Mehotd数组，该数组包含该类以及父类中所有的声明方法
   * 我们使用这个方法，而不是使用更比较易于理解的Class.getMethods()方法，
   * 是因为我们想同时找到私有方法，也就是说Class.getMethods()方法不能获得类的私有方法
   *
   * @param clazz The class
   * @return An array containing all methods in this class
   */
  private Method[] getClassMethods(Class<?> clazz) {
    // 唯一的方法Map
    Map<String, Method> uniqueMethods = new HashMap<>();

    // 当前的类
    Class<?> currentClass = clazz;

    // clazz类对象的实例不为null,且不是是超类的时候
    while (currentClass != null && currentClass != Object.class) {

      // 为每个方法生成唯一签名，并记录到uniqueMethods集合
      addUniqueMethods(uniqueMethods, currentClass.getDeclaredMethods());

      // we also need to look for interface methods -
      // because the class may be abstract
      // 翻译：我们还需要查找接口方法，因为当前类类可能是抽象类

      Class<?>[] interfaces = currentClass.getInterfaces();
      for (Class<?> anInterface : interfaces) {
        addUniqueMethods(uniqueMethods, anInterface.getMethods());
      }

      currentClass = currentClass.getSuperclass();
    }

    Collection<Method> methods = uniqueMethods.values();

    return methods.toArray(new Method[0]);
  }

  /**
   * 为每个方法生成唯一签名，并记录到uniqueMethods集合
   * @param uniqueMethods
   * @param methods
   */
  private void addUniqueMethods(Map<String, Method> uniqueMethods, Method[] methods) {
    // 遍历Method数组
    for (Method currentMethod : methods) {
      // 校验是否是桥接方法，因为桥接方法是编译器生成的，不需要
      if (!currentMethod.isBridge()) {
        // 生成方法签名
        String signature = getSignature(currentMethod);
        // check to see if the method is already known
        // if it is known, then an extended class must have
        // overridden a method
        // 将方法签名添加到uniqueMethods
        if (!uniqueMethods.containsKey(signature)) {
          uniqueMethods.put(signature, currentMethod);
        }
      }
    }
  }

  /**
   * 生成方法签名，签名格式： 方法返回类型名称#方法名:方法参数1,方法参数2...
   * @param method
   * @return
   */
  private String getSignature(Method method) {
    StringBuilder sb = new StringBuilder();
    Class<?> returnType = method.getReturnType();
    if (returnType != null) {
      sb.append(returnType.getName()).append('#');
    }
    sb.append(method.getName());
    Class<?>[] parameters = method.getParameterTypes();
    for (int i = 0; i < parameters.length; i++) {
      sb.append(i == 0 ? ':' : ',').append(parameters[i].getName());
    }
    return sb.toString();
  }

  /**
   * Checks whether can control member accessible.
   *
   * @return If can control member accessible, it return {@literal true}
   * @since 3.5.0
   */
  public static boolean canControlMemberAccessible() {
    try {
      SecurityManager securityManager = System.getSecurityManager();
      if (null != securityManager) {
        securityManager.checkPermission(new ReflectPermission("suppressAccessChecks"));
      }
    } catch (SecurityException e) {
      return false;
    }
    return true;
  }

  /**
   * Gets the name of the class the instance provides information for.
   *
   * @return The class name
   */
  public Class<?> getType() {
    return type;
  }

  public Constructor<?> getDefaultConstructor() {
    if (defaultConstructor != null) {
      return defaultConstructor;
    } else {
      throw new ReflectionException("There is no default constructor for " + type);
    }
  }

  public boolean hasDefaultConstructor() {
    return defaultConstructor != null;
  }

  public Invoker getSetInvoker(String propertyName) {
    Invoker method = setMethods.get(propertyName);
    if (method == null) {
      throw new ReflectionException("There is no setter for property named '" + propertyName + "' in '" + type + "'");
    }
    return method;
  }

  public Invoker getGetInvoker(String propertyName) {
    Invoker method = getMethods.get(propertyName);
    if (method == null) {
      throw new ReflectionException("There is no getter for property named '" + propertyName + "' in '" + type + "'");
    }
    return method;
  }

  /**
   * Gets the type for a property setter.
   *
   * @param propertyName - the name of the property
   * @return The Class of the property setter
   */
  public Class<?> getSetterType(String propertyName) {
    Class<?> clazz = setTypes.get(propertyName);
    if (clazz == null) {
      throw new ReflectionException("There is no setter for property named '" + propertyName + "' in '" + type + "'");
    }
    return clazz;
  }

  /**
   * Gets the type for a property getter.
   *
   * @param propertyName - the name of the property
   * @return The Class of the property getter
   */
  public Class<?> getGetterType(String propertyName) {
    Class<?> clazz = getTypes.get(propertyName);
    if (clazz == null) {
      throw new ReflectionException("There is no getter for property named '" + propertyName + "' in '" + type + "'");
    }
    return clazz;
  }

  /**
   * Gets an array of the readable properties for an object.
   *
   * @return The array
   */
  public String[] getGetablePropertyNames() {
    return readablePropertyNames;
  }

  /**
   * Gets an array of the writable properties for an object.
   *
   * @return The array
   */
  public String[] getSetablePropertyNames() {
    return writablePropertyNames;
  }

  /**
   * Check to see if a class has a writable property by name.
   *
   * @param propertyName - the name of the property to check
   * @return True if the object has a writable property by the name
   */
  public boolean hasSetter(String propertyName) {
    return setMethods.containsKey(propertyName);
  }

  /**
   * Check to see if a class has a readable property by name.
   *
   * @param propertyName - the name of the property to check
   * @return True if the object has a readable property by the name
   */
  public boolean hasGetter(String propertyName) {
    return getMethods.containsKey(propertyName);
  }

  public String findPropertyName(String name) {
    return caseInsensitivePropertyMap.get(name.toUpperCase(Locale.ENGLISH));
  }
}
