/**
 * Copyright 2016 Nikita Koksharov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.redisson.liveobject.misc;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import org.redisson.RedissonReference;
import org.redisson.client.codec.Codec;
import org.redisson.api.RLiveObject;
import org.redisson.api.RLiveObjectService;
import org.redisson.api.RObject;
import org.redisson.api.RObjectReactive;
import org.redisson.api.RedissonClient;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.api.annotation.REntity;
import org.redisson.api.annotation.RId;
import org.redisson.codec.CodecProvider;
import org.redisson.liveobject.resolver.NamingScheme;

/**
 *
 * @author Rui Gu (https://github.com/jackygurui)
 */
public class RedissonObjectFactory {

    private static final HashMap<Class, HashMap<Boolean, Method>> builders = new HashMap<Class, HashMap<Boolean, Method>>();
    private static final HashMap<Class, HashMap<Boolean, Method>> reactiveBuilders = new HashMap<Class, HashMap<Boolean, Method>>();

    static {
        for (Method method : RedissonClient.class.getDeclaredMethods()) {
            if (!method.getReturnType().equals(Void.TYPE)
                    && RObject.class.isAssignableFrom(method.getReturnType())
                    && method.getName().startsWith("get")) {
                Class<?> cls = method.getReturnType();
                if (!builders.containsKey(cls)) {
                    builders.put(cls, new HashMap<Boolean, Method>());
                }
                HashMap<Boolean, Method> builder = builders.get(cls);
                if (method.getParameterTypes().length == 2 //first param is name, second param is codec.
                        && Codec.class.isAssignableFrom(method.getParameterTypes()[1])) {
                    builder.put(Boolean.FALSE, method);
                } else if (method.getParameterTypes().length == 1) {
                    builder.put(Boolean.TRUE, method);
                }
            }
        }
        
        for (Method method : RedissonReactiveClient.class.getDeclaredMethods()) {
            if (!method.getReturnType().equals(Void.TYPE)
                    && RObjectReactive.class.isAssignableFrom(method.getReturnType())
                    && method.getName().startsWith("get")) {
                Class<?> cls = method.getReturnType();
                if (!reactiveBuilders.containsKey(cls)) {
                    reactiveBuilders.put(cls, new HashMap<Boolean, Method>());
                }
                HashMap<Boolean, Method> builder = reactiveBuilders.get(cls);
                if (method.getParameterTypes().length == 2 //first param is name, second param is codec.
                        && Codec.class.isAssignableFrom(method.getParameterTypes()[1])) {
                    builder.put(Boolean.FALSE, method);
                } else if (method.getParameterTypes().length == 1) {
                    builder.put(Boolean.TRUE, method);
                }
            }
        }
    }

    public static <T> T fromReference(RedissonClient redisson, RedissonReference rr) throws Exception {
        return fromReference(redisson, rr, null);
    }

    public static <T> T fromReference(RedissonClient redisson, RedissonReference rr, Class<?> expected) throws Exception {
        Class<? extends Object> type = rr.getType();
        CodecProvider codecProvider = redisson.getConfig().getCodecProvider();
        if (type != null) {
            if (type.isAnnotationPresent(REntity.class)) {
                RLiveObjectService liveObjectService = redisson.getLiveObjectService();
                REntity anno = type.getAnnotation(REntity.class);
                NamingScheme ns = anno.namingScheme()
                        .getDeclaredConstructor(Codec.class)
                        .newInstance(codecProvider.getCodec(anno, type));
                return (T) liveObjectService.getOrCreate(type, ns.resolveId(rr.getKeyName()));
            }
            List<Class<?>> interfaces = Arrays.asList(type.getInterfaces());
            for (Class<?> iType : interfaces) {
                if (builders.containsKey(iType)) {// user cache to speed up things a little.
                    Method builder = builders.get(iType).get(rr.isDefaultCodec());
                    return (T) (rr.isDefaultCodec()
                            ? builder.invoke(redisson, rr.getKeyName())
                            : builder.invoke(redisson, rr.getKeyName(), codecProvider.getCodec(rr.getCodecType())));
                }
            }
        }
        throw new ClassNotFoundException("No RObject is found to match class type of " + rr.getTypeName() + " with codec type of " + rr.getCodecName());
    }
    
    public static <T> T fromReference(RedissonReactiveClient redisson, RedissonReference rr) throws Exception {
        return fromReference(redisson, rr, null);
    }

    public static <T> T fromReference(RedissonReactiveClient redisson, RedissonReference rr, Class<?> expected) throws Exception {
        Class<? extends Object> type = rr.getReactiveType();
        CodecProvider codecProvider = redisson.getConfig().getCodecProvider();
        /**
         * Live Object from reference in reactive client is not supported yet.
         */
        if (type != null) {
            List<Class<?>> interfaces = Arrays.asList(type.getInterfaces());
            for (Class<?> iType : interfaces) {
                if (reactiveBuilders.containsKey(iType)) {// user cache to speed up things a little.
                    Method builder = reactiveBuilders.get(iType).get(rr.isDefaultCodec());
                    return (T) (rr.isDefaultCodec()
                            ? builder.invoke(redisson, rr.getKeyName())
                            : builder.invoke(redisson, rr.getKeyName(), codecProvider.getCodec(rr.getCodecType())));
                }
            }
        }
        throw new ClassNotFoundException("No RObjectReactive is found to match class type of " + rr.getReactiveTypeName()+ " with codec type of " + rr.getCodecName());
    }

    public static RedissonReference toReference(RedissonClient redisson, Object object) {
        if (object instanceof RObject) {
            RObject rObject = ((RObject) object);
            redisson.getCodecProvider().registerCodec((Class) rObject.getCodec().getClass(), (Class) rObject.getClass(), rObject.getName(), rObject.getCodec());
            return new RedissonReference(object.getClass(), ((RObject) object).getName(), ((RObject) object).getCodec());
        }
        try {
            if (object instanceof RLiveObject) {
                Class<? extends Object> rEntity = object.getClass().getSuperclass();
                REntity anno = rEntity.getAnnotation(REntity.class);
                NamingScheme ns = anno.namingScheme()
                        .getDeclaredConstructor(Codec.class)
                        .newInstance(redisson.getCodecProvider().getCodec(anno, (Class) rEntity));
                String name = Introspectior
                        .getFieldsWithAnnotation(rEntity, RId.class)
                        .getOnly().getName();
                Class<?> type = rEntity.getDeclaredField(name).getType();
                return new RedissonReference(rEntity,
                        ns.getName(rEntity, type, name, ((RLiveObject) object).getLiveObjectId()));
            }
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
        return null;
    }

    public static RedissonReference toReference(RedissonReactiveClient redissonReactive, Object object) {
        if (object instanceof RObjectReactive) {
            RObjectReactive rObject = ((RObjectReactive) object);
            redissonReactive.getCodecProvider().registerCodec((Class) rObject.getCodec().getClass(), (Class) rObject.getClass(), rObject.getName(), rObject.getCodec());
            return new RedissonReference(object.getClass(), ((RObjectReactive) object).getName(), ((RObjectReactive) object).getCodec());
        }
        try {
            if (object instanceof RLiveObject) {
                Class<? extends Object> rEntity = object.getClass().getSuperclass();
                REntity anno = rEntity.getAnnotation(REntity.class);
                NamingScheme ns = anno.namingScheme()
                        .getDeclaredConstructor(Codec.class)
                        .newInstance(redissonReactive.getCodecProvider().getCodec(anno, (Class) rEntity));
                String name = Introspectior
                        .getFieldsWithAnnotation(rEntity, RId.class)
                        .getOnly().getName();
                Class<?> type = rEntity.getDeclaredField(name).getType();
                return new RedissonReference(rEntity,
                        ns.getName(rEntity, type, name, ((RLiveObject) object).getLiveObjectId()));
            }
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
        return null;
    }

    public static <T extends RObject, K extends Codec> T createRObject(RedissonClient redisson, Class<T> expectedType, String name, K codec) throws Exception {
        List<Class<?>> interfaces = Arrays.asList(expectedType.getInterfaces());
        for (Class<?> iType : interfaces) {
            if (builders.containsKey(iType)) {// user cache to speed up things a little.
                Method builder = builders.get(iType).get(codec != null);
                return (T) (codec != null
                        ? builder.invoke(redisson, name)
                        : builder.invoke(redisson, name, codec));
            }
        }
        throw new ClassNotFoundException("No RObject is found to match class type of " + (expectedType != null ? expectedType.getName() : "null") + " with codec type of " + (codec != null ? codec.getClass().getName() : "null"));
    }

}
