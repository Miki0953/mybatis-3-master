Jdk8源码解读
=====================================
![mybatis](https://bkimg.cdn.bcebos.com/pic/962bd40735fae6cd10a6f2f103b30f2442a70f0b?x-bce-process=image/crop,x_8,y_0,w_583,h_385/watermark,g_7,image_d2F0ZXIvYmFpa2U4MA==,xp_5,yp_5)
-------------------------------------

把别人优秀的编程思想变成自己的！

-------------------------------------


基础模块
-------------------------------------
* Java类中属性和字段的区别
* Type接口及其常见实现类
  * Class：原始类型
  * ParameterizedType：参数化类型。例如List<String>、Map<Integer,String＞、Service<User＞这种带有泛型的类型。
  * TypeVariable：类型变量。它用来反映在JVM编译该泛型前的信息。例如List<T>中的T就是类型变量，它在编译时需被转换为 个具体的类型后才能正常使用。
  * GenericArrayType：数组类型，且组成元素是ParameterizedType和TypeVariable。例如：T[]
  * WildcardType:通配符类型，例如 ? extends Number 和 ? super Integer
* 什么是桥接方法
* StringBuffer和StringBuilder的区别
* mybatis-3.5.4用到了jdk8的一些新特性
  * Stream流
    * 过滤
    * 排序
    * 遍历
    * 统计
  * 函数式接口
    * 函数式接口和lambda表达式可以隐式地互转
    * Function接口只是函数式接口中的一个
    * Function接口作为方法参数的时候，lambda表达式相当于Function.apply()方法的具体实现
  * lambda表达式
  * 方法引用，可以看作是无参数lambda表达式的缩写
* 泛型的用法
  * ?通配符的含义
    * 表示不确定的是什么类型的类型
  * T、E、K、V通配符的区别
    * 实际上的意义都是一样的，只是为了增加阅读性，在源码中K\V
    表示键值对，T表示一种确定的类型，E表示元素Element
  * 上界通配符和下界通配符
    * 上界通配符：<? extends T>,表示参数化的类型可能是指定的类型，
    或者是此类型的子类
    * 下界通配符:<? super T>,表示参数化的类型可能是指定的类型，
    或者此类型的父类型
  * 泛型在定义方法返回类型和方法参数有重要作用

集合模块
-------------------------------------
* Map


反射
-------------------------------------
* Class类的理解
  * 面向对象的思维告诉我们，万物皆对象，所以Java编程语言中的各种类也
  是对象，我们把它们归为一个类，这个类就是Class.比如我们生活中有很多车，
  比如小汽车、卡车、火车，编程开发的时候我们把它们都归为一个类，就是Car类。
* Method类的理解以及它的常用方法
  * 如何获取方法返回类型
  * 如何获取方法参数列表
  * 如何获取方法名