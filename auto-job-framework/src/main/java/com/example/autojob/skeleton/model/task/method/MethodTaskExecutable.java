package com.example.autojob.skeleton.model.task.method;

import com.example.autojob.skeleton.framework.task.AutoJobTask;
import com.example.autojob.skeleton.model.executor.DefaultMethodObjectFactory;
import com.example.autojob.skeleton.model.executor.IMethodObjectFactory;
import com.example.autojob.skeleton.model.task.TaskExecutable;
import com.example.autojob.util.bean.ObjectUtil;
import com.example.autojob.util.convert.DefaultValueUtil;
import com.example.autojob.util.convert.StringUtils;

import java.lang.reflect.Method;

/**
 * Method型任务的包装
 *
 * @Author Huang Yongxiang
 * @Date 2022/08/02 11:14
 */
public class MethodTaskExecutable implements TaskExecutable {
    private final Method method;
    private Object methodObject;
    private Throwable throwable;
    private Object result;
    private final MethodTask task;

    public MethodTaskExecutable(MethodTask task) {
        if (task == null || task.getMethodClass() == null || StringUtils.isEmpty(task.getMethodName())) {
            throw new NullPointerException();
        }
        IMethodObjectFactory methodObjectFactory = task.getMethodObjectFactory() == null ? new DefaultMethodObjectFactory() : task.getMethodObjectFactory();
        if (task.getParams() != null) {
            this.method = ObjectUtil.findMethod(task.getMethodName(), task.getParams(), task.getMethodClass());
        } else {
            this.method = ObjectUtil.findMethod(task.getMethodName(), task.getMethodClass());
        }
        if (this.method == null) {
            throw new IllegalArgumentException("无指定方法");
        }
        this.methodObject = methodObjectFactory.createMethodObject(task.getMethodClassName());
        this.task = task;
    }

    @Override
    public Object execute(Object... params) throws Exception {
        if (method != null) {
            method.setAccessible(true);
            methodObject = DefaultValueUtil.defaultObjectWhenNull(methodObject, ObjectUtil.getClassInstance(method.getDeclaringClass()));
            if (params != null && params.length > 0) {
                result = method.invoke(methodObject, params);
            } else {
                result = method.invoke(methodObject);
            }
            return result;
        } else {
            Exception exception = new NullPointerException("空任务，无法执行");
            throwable = exception;
            throw exception;
        }
    }

    @Override
    public Object[] getExecuteParams() {
        return task.getParams();
    }

    @Override
    public AutoJobTask getAutoJobTask() {
        return task;
    }

    @Override
    public boolean isExecutable() {
        return method != null;
    }

    public Throwable getThrowable() {
        return throwable;
    }

    public Object getResult() {
        return result;
    }
}
