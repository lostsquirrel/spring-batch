/*
 * Copyright 2013-2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.batch.core.jsr.partition;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.locks.ReentrantLock;

import javax.batch.api.partition.PartitionAnalyzer;
import javax.batch.api.partition.PartitionCollector;
import javax.batch.api.partition.PartitionMapper;
import javax.batch.api.partition.PartitionPlan;

import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.JobExecutionException;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.jsr.configuration.support.BatchArtifact.BatchArtifactType;
import org.springframework.batch.core.jsr.configuration.support.BatchPropertyContext;
import org.springframework.batch.core.jsr.configuration.support.BatchPropertyContext.BatchPropertyContextEntry;
import org.springframework.batch.core.partition.JsrStepExecutionSplitter;
import org.springframework.batch.core.partition.PartitionHandler;
import org.springframework.batch.core.partition.StepExecutionSplitter;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.util.Assert;

/**
 * Executes a step instance per thread using a {@link ThreadPoolTaskExecutor} in
 * accordance with JSR-352.  The results from each step is aggregated into a
 * cumulative result.
 *
 * @author Michael Minella
 * @since 3.0
 */
public class JsrPartitionHandler implements PartitionHandler, InitializingBean {

	// TODO: Replace with proper Channel and Messages once minimum support level for Spring is 4
	private Queue<Serializable> partitionDataQueue;
	private ReentrantLock lock;
	private Step step;
	private int partitions;
	private PartitionAnalyzer analyzer;
	private PartitionMapper mapper;
	private int threads;
	private BatchPropertyContext propertyContext;
	private JobRepository jobRepository;
	private boolean allowStartIfComplete = false;

	public void setAllowStartIfComplete(boolean allowStartIfComplete) {
		this.allowStartIfComplete = allowStartIfComplete;
	}

	/**
	 * @param queue {@link Queue} to receive the output of the {@link PartitionCollector}
	 */
	public void setPartitionDataQueue(Queue<Serializable> queue) {
		this.partitionDataQueue = queue;
	}

	public void setPartitionLock(ReentrantLock lock) {
		this.lock = lock;
	}

	/**
	 * @param context {@link BatchPropertyContext} to resolve partition level step properties
	 */
	public void setPropertyContext(BatchPropertyContext context) {
		this.propertyContext = context;
	}

	/**
	 * @param mapper {@link PartitionMapper} used to configure partitioning
	 */
	public void setPartitionMapper(PartitionMapper mapper) {
		this.mapper = mapper;
	}

	/**
	 * @param step the step to be executed as a partitioned step
	 */
	public void setStep(Step step) {
		this.step = step;
	}

	/**
	 * @param analyzer {@link PartitionAnalyzer}
	 */
	public void setPartitionAnalyzer(PartitionAnalyzer analyzer) {
		this.analyzer = analyzer;
	}

	/**
	 * @param threads the number of threads to execute the partitions to be run
	 * within.  The default is the number of partitions.
	 */
	public void setThreads(int threads) {
		this.threads = threads;
	}

	/**
	 * @param partitions the number of partitions to be executed
	 */
	public void setPartitions(int partitions) {
		this.partitions = partitions;
	}

	/**
	 * @param jobRepository {@link JobRepository}
	 */
	public void setJobRepository(JobRepository jobRepository) {
		this.jobRepository = jobRepository;
	}

	/* (non-Javadoc)
	 * @see org.springframework.batch.core.partition.PartitionHandler#handle(org.springframework.batch.core.partition.StepExecutionSplitter, org.springframework.batch.core.StepExecution)
	 */
	@Override
	public Collection<StepExecution> handle(StepExecutionSplitter stepSplitter,
			StepExecution stepExecution) throws Exception {
		final List<Future<StepExecution>> tasks = new ArrayList<Future<StepExecution>>();
		final Set<StepExecution> result = new HashSet<StepExecution>();
		final ThreadPoolTaskExecutor taskExecutor = new ThreadPoolTaskExecutor();

		int stepExecutionCount = jobRepository.getStepExecutionCount(stepExecution.getJobExecution().getJobInstance(), stepExecution.getStepName());

		boolean isRestart = stepExecutionCount > 1;

		Set<StepExecution> partitionStepExecutions = splitStepExecution(stepExecution, isRestart);

		taskExecutor.setCorePoolSize(threads);
		taskExecutor.setMaxPoolSize(threads);

		taskExecutor.initialize();

		for (final StepExecution curStepExecution : partitionStepExecutions) {
			final FutureTask<StepExecution> task = createTask(step, curStepExecution);

			try {
				taskExecutor.execute(task);
				tasks.add(task);
			} catch (TaskRejectedException e) {
				// couldn't execute one of the tasks
				ExitStatus exitStatus = ExitStatus.FAILED
						.addExitDescription("TaskExecutor rejected the task for this step.");
				/*
				 * Set the status in case the caller is tracking it through the
				 * JobExecution.
				 */
				curStepExecution.setStatus(BatchStatus.FAILED);
				curStepExecution.setExitStatus(exitStatus);
				result.add(stepExecution);
			}
		}

		processPartitionResults(tasks, result);

		return result;
	}

	private void processPartitionResults(
			final List<Future<StepExecution>> tasks,
			final Set<StepExecution> result) throws Exception {
		while(true) {
			try {
				lock.lock();
				while(!partitionDataQueue.isEmpty()) {
					analyzer.analyzeCollectorData(partitionDataQueue.remove());
				}

				processFinishedPartitions(tasks, result);

				if(tasks.size() == 0) {
					break;
				}
			} finally {
				if(lock.isHeldByCurrentThread()) {
					lock.unlock();
				}
			}
		}
	}

	private Set<StepExecution> splitStepExecution(StepExecution stepExecution,
			boolean isRestart) throws Exception, JobExecutionException {
		Set<StepExecution> partitionStepExecutions = new HashSet<StepExecution>();
		if(isRestart) {
			if(mapper != null) {
				PartitionPlan plan = mapper.mapPartitions();

				if(plan.getPartitionsOverride()) {
					partitionStepExecutions = applyPartitionPlan(stepExecution, plan, false);

					for (StepExecution curStepExecution : partitionStepExecutions) {
						curStepExecution.setExecutionContext(new ExecutionContext());
					}
				} else {
					Properties[] partitionProps = plan.getPartitionProperties();

					plan = (PartitionPlanState) stepExecution.getExecutionContext().get("partitionPlanState");
					plan.setPartitionProperties(partitionProps);

					partitionStepExecutions = applyPartitionPlan(stepExecution, plan, true);
				}

			} else {
				StepExecutionSplitter stepSplitter = new JsrStepExecutionSplitter(jobRepository, allowStartIfComplete, stepExecution.getStepName(), true);
				partitionStepExecutions = stepSplitter.split(stepExecution, partitions);
			}
		} else {
			if(mapper != null) {
				PartitionPlan plan = mapper.mapPartitions();
				partitionStepExecutions = applyPartitionPlan(stepExecution, plan, true);
			} else {
				StepExecutionSplitter stepSplitter = new JsrStepExecutionSplitter(jobRepository, allowStartIfComplete, stepExecution.getStepName(), true);
				partitionStepExecutions = stepSplitter.split(stepExecution, partitions);
			}
		}
		return partitionStepExecutions;
	}

	private Set<StepExecution> applyPartitionPlan(StepExecution stepExecution,
			PartitionPlan plan, boolean restoreState) throws JobExecutionException {
		StepExecutionSplitter stepSplitter;
		Set<StepExecution> partitionStepExecutions;
		if(plan.getThreads() > 0) {
			threads = plan.getThreads();
		} else if(plan.getPartitions() > 0) {
			threads = plan.getPartitions();
		} else {
			throw new IllegalArgumentException("Either a number of threads or partitions are required");
		}

		stepExecution.getExecutionContext().put("partitionPlanState", new PartitionPlanState(plan));

		stepSplitter = new JsrStepExecutionSplitter(jobRepository, allowStartIfComplete, stepExecution.getStepName(), restoreState);
		partitionStepExecutions = stepSplitter.split(stepExecution, plan.getPartitions());
		registerPartitionProperties(partitionStepExecutions, plan);
		return partitionStepExecutions;
	}

	private void processFinishedPartitions(
			final List<Future<StepExecution>> tasks,
			final Set<StepExecution> result) throws Exception {
		for(int i = 0; i < tasks.size(); i++) {
			Future<StepExecution> curTask = tasks.get(i);

			if(curTask.isDone()) {
				StepExecution curStepExecution = curTask.get();

				if(analyzer != null) {
					analyzer.analyzeStatus(curStepExecution.getStatus().getBatchStatus(), curStepExecution.getExitStatus().getExitCode());
				}

				result.add(curStepExecution);

				tasks.remove(i);
				i--;
			}
		}
	}

	private void registerPartitionProperties(
			Set<StepExecution> partitionStepExecutions, PartitionPlan plan) {
		Properties[] partitionProperties = plan.getPartitionProperties();
		if(partitionProperties != null) {
			Iterator<StepExecution> executions = partitionStepExecutions.iterator();

			int i = 0;
			while(executions.hasNext()) {
				StepExecution curExecution = executions.next();

				if(i < partitionProperties.length) {
					Properties partitionPropertyValues = partitionProperties[i];
					if(partitionPropertyValues != null) {
						List<BatchPropertyContextEntry> entries = new ArrayList<BatchPropertyContext.BatchPropertyContextEntry>();
						BatchPropertyContextEntry entry = propertyContext.new BatchPropertyContextEntry(curExecution.getStepName(), partitionPropertyValues, BatchArtifactType.STEP);
						entries.add(entry);

						propertyContext.setStepPropertiesContextEntry(entries);
					}

					i++;
				} else {
					break;
				}
			}
		}
	}

	/**
	 * Creates the task executing the given step in the context of the given execution.
	 *
	 * @param step the step to execute
	 * @param stepExecution the given execution
	 * @return the task executing the given step
	 */
	protected FutureTask<StepExecution> createTask(final Step step,
			final StepExecution stepExecution) {
		return new FutureTask<StepExecution>(new Callable<StepExecution>() {
			@Override
			public StepExecution call() throws Exception {
				step.execute(stepExecution);
				return stepExecution;
			}
		});
	}

	/* (non-Javadoc)
	 * @see org.springframework.beans.factory.InitializingBean#afterPropertiesSet()
	 */
	@Override
	public void afterPropertiesSet() throws Exception {
		Assert.notNull(propertyContext, "A BatchPropertyContext is required");
		Assert.isTrue(mapper != null || threads > 0, "Either a mapper implementation or the number of partitions/threads is required");
		Assert.notNull(jobRepository, "A JobRepository is required");

		if(partitionDataQueue == null) {
			partitionDataQueue = new LinkedBlockingQueue<Serializable>();
		}

		if(lock == null) {
			lock = new ReentrantLock();
		}
	}

	/**
	 * Since a {@link PartitionPlan} could provide dynamic data (different results from run to run),
	 * the batch runtime needs to save off the results for restarts.  This class serves as a container
	 * used to save off that state.
	 *
	 * @author Michael Minella
	 * @since 3.0
	 */
	public static class PartitionPlanState implements PartitionPlan, Serializable {

		private static final long serialVersionUID = 1L;
		private Properties[] partitionProperties;
		private int partitions;
		private int threads;

		/**
		 * @param plan the {@link PartitionPlan} that is the source of the state
		 */
		public PartitionPlanState(PartitionPlan plan) {
			partitionProperties = plan.getPartitionProperties();
			partitions = plan.getPartitions();
			threads = plan.getThreads();
		}

		/* (non-Javadoc)
		 * @see javax.batch.api.partition.PartitionPlan#getPartitionProperties()
		 */
		@Override
		public Properties[] getPartitionProperties() {
			return partitionProperties;
		}

		/* (non-Javadoc)
		 * @see javax.batch.api.partition.PartitionPlan#getPartitions()
		 */
		@Override
		public int getPartitions() {
			return partitions;
		}

		/* (non-Javadoc)
		 * @see javax.batch.api.partition.PartitionPlan#getThreads()
		 */
		@Override
		public int getThreads() {
			return threads;
		}

		/* (non-Javadoc)
		 * @see javax.batch.api.partition.PartitionPlan#setPartitions(int)
		 */
		@Override
		public void setPartitions(int count) {
			this.partitions = count;
		}

		/* (non-Javadoc)
		 * @see javax.batch.api.partition.PartitionPlan#setPartitionsOverride(boolean)
		 */
		@Override
		public void setPartitionsOverride(boolean override) {
			// Intentional No-op
		}

		/* (non-Javadoc)
		 * @see javax.batch.api.partition.PartitionPlan#getPartitionsOverride()
		 */
		@Override
		public boolean getPartitionsOverride() {
			return false;
		}

		/* (non-Javadoc)
		 * @see javax.batch.api.partition.PartitionPlan#setThreads(int)
		 */
		@Override
		public void setThreads(int count) {
			this.threads = count;
		}

		/* (non-Javadoc)
		 * @see javax.batch.api.partition.PartitionPlan#setPartitionProperties(java.util.Properties[])
		 */
		@Override
		public void setPartitionProperties(Properties[] props) {
			this.partitionProperties = props;
		}
	}
}
