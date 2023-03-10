package com.example.autojob.api.task;

import com.example.autojob.api.task.params.MethodTaskEditParams;
import com.example.autojob.api.task.params.ScriptTaskEditParams;
import com.example.autojob.api.task.params.TaskEditParams;
import com.example.autojob.api.task.params.TriggerEditParams;
import com.example.autojob.skeleton.annotation.AutoJobRPCService;
import com.example.autojob.skeleton.framework.boot.AutoJobApplication;
import com.example.autojob.skeleton.framework.container.MemoryTaskContainer;
import com.example.autojob.skeleton.framework.task.AutoJobTask;
import com.example.autojob.skeleton.framework.task.AutoJobTrigger;
import com.example.autojob.skeleton.model.builder.AutoJobTriggerFactory;
import com.example.autojob.skeleton.model.executor.IMethodObjectFactory;
import com.example.autojob.skeleton.model.interpreter.AutoJobAttributeContext;
import com.example.autojob.skeleton.model.task.method.MethodTask;
import com.example.autojob.skeleton.model.task.script.ScriptTask;
import com.example.autojob.util.bean.ObjectUtil;
import com.example.autojob.util.convert.DateUtils;
import com.example.autojob.util.convert.StringUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Memory任务一站式API，该类能被框架内置RPC客户端调用
 *
 * @Author Huang Yongxiang
 * @Date 2022/10/17 15:40
 */
@AutoJobRPCService("MemoryTaskAPI")
@Slf4j
public class MemoryTaskAPI implements AutoJobAPI {
    private final MemoryTaskContainer container = AutoJobApplication
            .getInstance()
            .getMemoryTaskContainer();

    @Override
    public List<AutoJobTaskAttributes> page(Integer pageNum, Integer size) {
        List<AutoJobTask> tasks = container.list();
        if (tasks.size() == 0) {
            return Collections.emptyList();
        }
        int skip = (pageNum - 1) * size;
        int startIndex = Math.min(tasks.size(), skip);
        return tasks
                .subList(startIndex, Math.min(tasks.size(), startIndex + size))
                .stream()
                .map(task -> {
                    if (task instanceof MethodTask) {
                        return new AutoJobMethodTaskAttributes((MethodTask) task);
                    } else if (task instanceof ScriptTask) {
                        return new AutoJobScriptTaskAttributes((ScriptTask) task);
                    }
                    return null;
                })
                .collect(Collectors.toList());
    }

    @Override
    public Integer count() {
        return container.size();
    }

    @Override
    public Boolean registerTask(AutoJobTaskAttributes taskAttributes) {
        if (ObjectUtil.isNull(taskAttributes)) {
            return false;
        }
        AutoJobTask task = taskAttributes.convert();
        if (task.getTrigger() == null || task.getType() != AutoJobTask.TaskType.MEMORY_TASk) {
            return false;
        }
        try {
            container.insert(task);
            return true;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    @Override
    public Boolean runTaskNow(AutoJobTaskAttributes taskAttributes) {
        if (ObjectUtil.isNull(taskAttributes)) {
            return false;
        }
        AutoJobTask task = taskAttributes.convert();
        if (task.getType() != AutoJobTask.TaskType.MEMORY_TASk) {
            return false;
        }
        AutoJobTrigger trigger = AutoJobTriggerFactory.newDelayTrigger(5, TimeUnit.SECONDS);
        task.setTrigger(trigger);
        try {
            container.insert(task);
            return true;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    @Override
    public AutoJobTaskAttributes find(Long taskId) {
        AutoJobTask task = container.getById(taskId);
        if (task instanceof MethodTask) {
            return new AutoJobMethodTaskAttributes((MethodTask) task);
        } else if (task instanceof ScriptTask) {
            return new AutoJobScriptTaskAttributes((ScriptTask) task);
        }
        return null;
    }

    /**
     * 修改内存任务的触发器参数，注意该方法只允许修改cron-like表达式、重复次数、周期、触发时间、子任务、最大运行时长以及暂停调度
     *
     * @param taskId            要修改的任务
     * @param triggerEditParams 修改的信息
     * @return java.lang.Boolean
     * @author Huang Yongxiang
     * @date 2022/10/27 16:50
     */
    @Override
    public Boolean editTrigger(Long taskId, TriggerEditParams triggerEditParams) {
        if (ObjectUtil.isNull(triggerEditParams)) {
            return false;
        }
        AutoJobTask task = container.getById(taskId);
        if (task == null || task.getTrigger() == null) {
            return false;
        }
        if (pause(taskId)) {
            //log.warn("任务开始修改");
            try {
                AutoJobTrigger editParams = new AutoJobTrigger();
                editParams.setCronExpression(triggerEditParams.getCronExpression());
                editParams.setRepeatTimes(triggerEditParams.getRepeatTimes());
                editParams.setCycle(triggerEditParams.getCycle());
                if (!StringUtils.isEmpty(triggerEditParams.getChildTasksId())) {
                    String[] idArray = triggerEditParams
                            .getChildTasksId()
                            .split(",");
                    editParams.setChildTask(Arrays
                            .stream(idArray)
                            .map(Long::parseLong)
                            .collect(Collectors.toList()));
                }
                editParams.setMaximumExecutionTime(triggerEditParams.getMaximumExecutionTime());
                ObjectUtil.mergeObject(editParams, task.getTrigger());
            } finally {
                unpause(taskId);
            }
            //log.warn("任务修改完成");
            return true;
        }
        return false;
    }

    @Override
    public Boolean bindingTrigger(Long taskId, AutoJobTrigger trigger) {
        if (taskId == null || trigger == null) {
            return false;
        }
        try {
            if (pause(taskId)) {
                AutoJobTask task = container.getById(taskId);
                if (task == null) {
                    return false;
                }
                trigger.setTaskId(taskId);
                task.setTrigger(trigger);
                if (trigger.isNearTriggeringTime(5000)) {
                    AutoJobApplication
                            .getInstance()
                            .getRegister()
                            .registerTask(task);
                }
                log.info("任务{}将在{}执行", taskId, DateUtils.formatDateTime(new Date(trigger.getTriggeringTime())));
                return true;
            }
        } finally {
            unpause(taskId);
        }
        return false;
    }

    /**
     * 修改内存任务信息，任务ID，注解ID和任务类型不允许修改
     *
     * @param taskId         要修改的任务ID
     * @param taskEditParams 修改的内容
     * @return java.lang.Boolean
     * @author Huang Yongxiang
     * @date 2022/10/27 17:52
     */
    @Override
    @SuppressWarnings("unchecked")
    public Boolean editTask(Long taskId, TaskEditParams taskEditParams) {
        AutoJobTask edit = container.getById(taskId);
        if (edit == null || ObjectUtil.isNull(taskEditParams)) {
            return false;
        }
        if (!pause(taskId)) {
            return false;
        }
        try {
            if (taskEditParams instanceof MethodTaskEditParams) {
                MethodTask task = new MethodTask();
                task.setAlias(taskEditParams.getAlias());
                task.setBelongTo(taskEditParams.getBelongTo());
                task.setTaskLevel(taskEditParams.getTaskLevel());
                if (!StringUtils.isEmpty(((MethodTaskEditParams) taskEditParams).getParamsString())) {
                    task.setParamsString(((MethodTaskEditParams) taskEditParams).getParamsString());
                    task.setParams(new AutoJobAttributeContext(task.getParamsString()).getAttributeEntity());
                }

                if (!StringUtils.isEmpty(((MethodTaskEditParams) taskEditParams).getMethodObjectFactory())) {
                    Class<IMethodObjectFactory> factoryClass = (Class<IMethodObjectFactory>) ObjectUtil.classPath2Class(((MethodTaskEditParams) taskEditParams).getMethodObjectFactory());
                    if (factoryClass == null) {
                        return false;
                    }
                    task.setMethodObjectFactory(ObjectUtil.getClassInstance(factoryClass));
                }
                ObjectUtil.mergeObject(task, edit);
                return true;
            } else if (taskEditParams instanceof ScriptTaskEditParams) {
                ScriptTask task = new ScriptTask();
                task.setAlias(taskEditParams.getAlias());
                task.setBelongTo(taskEditParams.getBelongTo());
                task.setTaskLevel(taskEditParams.getTaskLevel());
                task.setParams(((ScriptTaskEditParams) taskEditParams)
                        .getAttributes()
                        .toArray());
                ObjectUtil.mergeObject(task, edit);
                return true;
            }
        } finally {
            unpause(taskId);
        }
        return false;
    }

    @Override
    public Boolean pause(Long taskId) {
        AutoJobTask task = container.getById(taskId);
        if (task == null) {
            return false;
        }
        if (task.getTrigger() == null) {
            return true;
        }
        task
                .getTrigger()
                .setIsPause(true);
        AutoJobApplication
                .getInstance()
                .getRegister()
                .removeTask(taskId);
        return true;
    }

    @Override
    public Boolean unpause(Long taskId) {
        AutoJobTask task = container.getById(taskId);
        if (task == null || task.getTrigger() == null) {
            return false;
        }
        task
                .getTrigger()
                .setIsPause(false);
        if (task
                .getTrigger()
                .getTriggeringTime() == null || task
                .getTrigger()
                .getTriggeringTime() < System.currentTimeMillis()) {
            task
                    .getTrigger()
                    .refresh();
        }
        return true;
    }

    @Override
    public Boolean delete(Long taskId) {
        return pause(taskId) && container.removeById(taskId) != null;
    }

    @Override
    public Boolean isExist(Long taskId) {
        return container.getById(taskId) != null;
    }

    public static void main(String[] args) {
        Map<Integer, String> codes = new HashMap<>(10000);
        boolean flag = false;
        int cCount = 0;
        int loop = 5000000;
        Map<String, String> cCodes = new HashMap<>();
        for (int i = 0; i < loop; i++) {
            String str = randomClassPath(10);
            //System.out.println(str);
            int h = str.hashCode();
            h = Math.abs(h ^ (h >>> 16));
            if (i % 10000 == 0) {
                System.out.printf("测试进度：%.4f\n", (i * 100.0 / loop));
            }
            if (codes.containsKey(h) && !codes
                    .get(h)
                    .equals(str)) {
                //System.out.printf("发生hash冲突：字符串：%s同%s，冲突hash码：%d\n", str, codes.get(h), h);
                if (cCodes.size() < 50) {
                    cCodes.put(codes.get(h), str);
                }
                flag = true;
                cCount++;
            }
            codes.put(h, str);
        }
        if (!flag) {
            System.out.println("测试完毕没有发生hash冲突");
        } else {
            for (Map.Entry<String, String> entry : cCodes.entrySet()) {
                System.out.printf("冲突路径：%s和%s\n", entry.getKey(), entry.getValue());
            }
            System.out.printf("检测完成，共计测试字符串%d个，冲突%d个，冲突率：%.5f%%\n", codes.size(), cCount, (cCount * 100.0) / codes.size());
        }
    }

    static String randomClassPath(int deep) {
        Random random = new Random();
        int rDeep = random.nextInt(deep) + 2;
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < rDeep; i++) {
            builder.append(StringUtils.getRandomStr(random.nextInt(16) + 1));
            if (i < rDeep - 1) {
                builder.append(".");
            }
        }
        return builder.toString();
    }
}
