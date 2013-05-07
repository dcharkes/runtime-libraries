package org.metaborg.runtime.task;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.spoofax.interpreter.core.Tools;
import org.spoofax.interpreter.terms.IStrategoAppl;
import org.spoofax.interpreter.terms.IStrategoConstructor;
import org.spoofax.interpreter.terms.IStrategoInt;
import org.spoofax.interpreter.terms.IStrategoList;
import org.spoofax.interpreter.terms.IStrategoTerm;
import org.spoofax.interpreter.terms.IStrategoTuple;
import org.spoofax.interpreter.terms.ITermFactory;
import org.strategoxt.lang.Context;
import org.strategoxt.lang.Strategy;

public class TaskEvaluator {
	private final TaskEngine taskEngine;
	private final ITermFactory factory;
	private final IStrategoConstructor dependencyConstructor;


	/** Set of task that are scheduled for evaluation the next time evaluate is called. */
	private final Set<IStrategoInt> nextScheduled = new HashSet<IStrategoInt>();

	/** Queue of task that are scheduled for evaluation. */
	private final Queue<IStrategoInt> evaluationQueue = new ConcurrentLinkedQueue<IStrategoInt>();

	/** Dependencies of tasks which are updated during evaluation. */
	private final ManyToManyMap<IStrategoInt, IStrategoInt> toRuntimeDependency = ManyToManyMap.create();

	private final ManyToManyMap<IStrategoInt, IStrategoInt> hadDynamicDependency = ManyToManyMap.create();

	public TaskEvaluator(TaskEngine taskEngine, ITermFactory factory) {
		this.taskEngine = taskEngine;
		this.factory = factory;
		this.dependencyConstructor = factory.makeConstructor("Dependency", 1);
	}

	/**
	 * Schedules a task with unknown dependencies for evaluation.
	 * 
	 * @param taskID Task identifier to schedule.
	 */
	public void schedule(IStrategoInt taskID) {
		nextScheduled.add(taskID);
	}

	private void queue(IStrategoInt taskID) {
		evaluationQueue.offer(taskID);
		System.out.println("Queueing: " + taskID + " - " + taskEngine.getInstruction(taskID));
	}

	public IStrategoTuple evaluate(Context context, Strategy performInstruction, Strategy insertResults) {
		try {
			// Remove solutions and reads for tasks that are scheduled for evaluation.
			for(final IStrategoInt taskID : nextScheduled) {
				taskEngine.removeSolved(taskID);
				taskEngine.removeReads(taskID);
			}

			// Fill toRuntimeDependency for scheduled tasks such that solving the task activates their dependent tasks.
			for(final IStrategoInt taskID : nextScheduled) {
				final Set<IStrategoInt> dependencies = new HashSet<IStrategoInt>(taskEngine.getDependencies(taskID));
				for(final IStrategoInt dependency : taskEngine.getDependencies(taskID)) {
					if(taskEngine.isSolved(dependency))
						dependencies.remove(dependency);
				}

				// If the task has no unsolved dependencies, queue it for analysis.
				if(dependencies.isEmpty()) {
					queue(taskID);
				} else {
					toRuntimeDependency.putAll(taskID, dependencies);
				}
			}

			// Evaluate each task in the queue.
			int numTasksEvaluated = 0;
			for(IStrategoInt taskID; (taskID = evaluationQueue.poll()) != null;) {
				++numTasksEvaluated;
				final IStrategoTerm instruction = taskEngine.getInstruction(taskID);
				final IStrategoTerm result = solve(context, performInstruction, insertResults, taskID, instruction);
				if(result != null && Tools.isTermAppl(result)) {
					// The task has dynamic dependencies.
					final IStrategoAppl resultAppl = (IStrategoAppl) result;
					if(resultAppl.getConstructor().equals(dependencyConstructor)) {
						updateDelayedDependencies(taskID, (IStrategoList) resultAppl.getSubterm(0));
					} else { 
						throw new IllegalStateException("Unexpected result from perform-task(|taskID): " + result
							+ ". Must be a list, Dependency(_) constructor or failure.");
					}
				} else if(result == null) {
					// The task failed to produce a result.
					taskEngine.addFailed(taskID);
					nextScheduled.remove(taskID);
					hadDynamicDependency.removeAll(taskID);
					tryScheduleNewTasks(taskID);
				} else if(Tools.isTermList(result)) {
					// The task produced a result.
					taskEngine.addResult(taskID, (IStrategoList) result);
					nextScheduled.remove(taskID);
					hadDynamicDependency.removeAll(taskID);
					tryScheduleNewTasks(taskID);
				} else {
					throw new IllegalStateException("Unexpected result from perform-task(|taskID): " + result
						+ ". Must be a list, Dependency(_) constructor or failure.");
				}
			}
			
			for(Entry<IStrategoInt, Collection<IStrategoInt>> i : hadDynamicDependency.asMap().entrySet()) {
				System.err.println("Task " + i.getKey() + " - " + taskEngine.getInstruction(i.getKey()) + " still has dynamic dependencies on: " + factory.makeList(i.getValue()));
			}
			
			Set<IStrategoInt> roots = new HashSet<IStrategoInt>();
			for(IStrategoInt taskID : nextScheduled) {
				Set<IStrategoInt> seen = new HashSet<IStrategoInt>();
				findRoot(taskID, roots, seen);
			}
			
			for(IStrategoInt root : roots) {
				System.err.println("Task " + root + " - " + taskEngine.getInstruction(root) + " / " + factory.makeList(taskEngine.getDependencies(root)) + " is a root blocker.");
			}
			
			return factory.makeTuple(factory.makeList(nextScheduled), factory.makeInt(numTasksEvaluated));
		} finally {
			reset();
		}
	}
	
	private void findRoot(IStrategoInt taskID, Set<IStrategoInt> roots, Set<IStrategoInt> seen) {
		seen.add(taskID);
		for(IStrategoInt dependency : taskEngine.getDependencies(taskID)) {
			if(!taskEngine.isSolved(dependency) && nextScheduled.contains(dependency)) {
				if(seen.contains(dependency))
					System.err.println("Cycle: " + taskID + " already seen " + dependency);
				else
					findRoot(dependency, roots, seen);
			} else {
				roots.add(dependency);
			}
		}
	}

	public void reset() {
		nextScheduled.clear();
		evaluationQueue.clear();
		toRuntimeDependency.clear();
	}

	private IStrategoTerm solve(Context context, Strategy performInstruction, Strategy insertResults,
		IStrategoInt taskID, IStrategoTerm instruction) {
		final IStrategoTerm insertedInstruction = insertResults(context, insertResults, instruction);
		return performInstruction.invoke(context, insertedInstruction, taskID);
	}

	private IStrategoTerm insertResults(Context context, Strategy insertResults, IStrategoTerm instruction) {
		return insertResults.invoke(context, instruction);
	}

	private void tryScheduleNewTasks(IStrategoInt solved) {
		// Retrieve dependent tasks of the solved task.
		final Set<IStrategoInt> dependents = new HashSet<IStrategoInt>(taskEngine.getDependent(solved));
		// Make a copy for toRuntimeDependency because a remove operation can occur while iterating.
		dependents.addAll(toRuntimeDependency.getInverse(solved));

		for(final IStrategoInt dependent : dependents) {
			// Retrieve dependencies for a dependent task.
			Collection<IStrategoInt> dependencies = toRuntimeDependency.get(dependent);
			/*int dependenciesSize = dependencies.size();
			if(dependenciesSize == 0) {
				// If toRuntimeDependency does not contain dependencies for dependent yet, add them.
				dependencies = taskEngine.getDependencies(dependent);
				dependenciesSize = dependencies.size();
				toRuntimeDependency.putAll(dependent, dependencies);
			}*/

			// Remove the dependency to the solved task. If that was the last dependency, schedule the task.
			toRuntimeDependency.remove(dependent, solved);
			if(dependencies.size() == 0 && !taskEngine.isSolved(dependent))
				queue(dependent);
		}
	}

	private void updateDelayedDependencies(IStrategoInt delayed, IStrategoList dependencies) {
		// Sets the runtime dependencies for a task to the given dependency list.
		toRuntimeDependency.removeAll(delayed);
		for(final IStrategoTerm dependency : dependencies) {
			if(taskEngine.isSolved((IStrategoInt) dependency)) {
				System.err.println("Task " + dependency + " is solved, it cannot be a dependency! From: " + taskEngine.getInstruction(delayed));
			} 
			
			toRuntimeDependency.put(delayed, (IStrategoInt) dependency);
			hadDynamicDependency.put(delayed, (IStrategoInt) dependency);
		}
		
		System.out.println("DDep: " + delayed + " -  " + taskEngine.getInstruction(delayed) + " -> " + dependencies);
	}
}
