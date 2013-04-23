package org.metaborg.runtime.task;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.spoofax.interpreter.core.Tools;
import org.spoofax.interpreter.library.ssl.StrategoHashMap;
import org.spoofax.interpreter.terms.IStrategoAppl;
import org.spoofax.interpreter.terms.IStrategoConstructor;
import org.spoofax.interpreter.terms.IStrategoInt;
import org.spoofax.interpreter.terms.IStrategoList;
import org.spoofax.interpreter.terms.IStrategoTerm;
import org.spoofax.interpreter.terms.IStrategoTuple;
import org.spoofax.interpreter.terms.ITermFactory;
import org.strategoxt.lang.Context;
import org.strategoxt.lang.Strategy;

import com.google.common.collect.Iterables;
import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Multimap;

public class TaskEvaluator {
	private final TaskEngine taskEngine;
	private final ITermFactory factory;
	private final IStrategoConstructor dependencyConstructor;
	private final IStrategoConstructor singleConstructor;


	/** Set of task that are scheduled for evaluation the next time evaluate is called. */
	private final Set<IStrategoInt> nextScheduled = new HashSet<IStrategoInt>();

	/** Queue of task that are scheduled for evaluation. */
	private final Queue<IStrategoInt> evaluationQueue = new ConcurrentLinkedQueue<IStrategoInt>();

	/** Dependencies of tasks which are updated during evaluation. */
	private final ManyToManyMap<IStrategoInt, IStrategoInt> toRuntimeDependency = ManyToManyMap.create();


	public TaskEvaluator(TaskEngine taskEngine, ITermFactory factory) {
		this.taskEngine = taskEngine;
		this.factory = factory;
		this.dependencyConstructor = factory.makeConstructor("Dependency", 1);
		this.singleConstructor = factory.makeConstructor("Single", 1);
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
		evaluationQueue.add(taskID);
	}

	public void reset() {
		nextScheduled.clear();
		evaluationQueue.clear();
		toRuntimeDependency.clear();
	}

	public IStrategoTuple evaluate(Context context, Strategy collectResults, Strategy insertResults,
		Strategy performInstruction) {
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
					evaluationQueue.add(taskID);
				} else {
					toRuntimeDependency.putAll(taskID, dependencies);
				}
			}

			// Evaluate each task in the queue.
			int numTasksEvaluated = 0;
			for(IStrategoInt taskID; (taskID = evaluationQueue.poll()) != null;) {
				++numTasksEvaluated;
				final IStrategoTerm instruction = taskEngine.getInstruction(taskID);
				final Collection<IStrategoTerm> instructions =
					instructionCombinations(context, collectResults, insertResults, instruction);

				boolean fail = false;
				boolean success = false;
				for(IStrategoTerm insertedInstruction : instructions) {
					final IStrategoTerm result = solve(context, performInstruction, taskID, insertedInstruction);
					final ResultType resultType = handleResult(taskID, instruction, result);
					switch(resultType) {
					case Fail:
						fail = true;
						break;
					case Success:
						success = true;
						break;
					case Unknown:
						throw new IllegalStateException("Unexpected result from perform-task(|taskID): " + result + ".");
					default:
						break;
					}
				}

				if(fail || success) {
					// TODO: should completely failed tasks activate other tasks?
					tryScheduleNewTasks(taskID);

					if(success) {
						nextScheduled.remove(instruction);
					} else {
						taskEngine.addFailed(taskID);
					}
				}
			}

			return factory.makeTuple(factory.makeList(nextScheduled), factory.makeInt(numTasksEvaluated));
		} finally {
			reset();
		}
	}

	private Collection<IStrategoTerm> instructionCombinations(Context context, Strategy collectResults,
		Strategy insertResults, IStrategoTerm instruction) {
		final IStrategoTerm resultIDs = collectResults.invoke(context, instruction);
		final Collection<IStrategoTerm> instructions = new ArrayList<IStrategoTerm>();

		// TODO: insert and collect results in Java instead of an external strategy?

		if(!isTaskCombinator(instruction)) {
			// TODO: prevent construction of a multimap by changing cartesianProduct to accept a list of task IDs.
			final Multimap<IStrategoInt, IStrategoTerm> results = LinkedHashMultimap.create();
			for(IStrategoTerm resultID : resultIDs) {
				IStrategoInt key = (IStrategoInt) resultID;
				results.putAll(key, taskEngine.getResults(key));
			}

			final Collection<StrategoHashMap> resultCombinations = Utils.cartesianProduct(results);
			for(StrategoHashMap mapping : resultCombinations) {
				printMapping(mapping);
				System.out.println("Inserting into task " + instruction);
				instructions.add(insertResults(context, insertResults, instruction, mapping));
			}
		} else {
			StrategoHashMap mapping = new StrategoHashMap();
			for(IStrategoTerm resultID : resultIDs) {
				final Collection<IStrategoTerm> results = taskEngine.getResults((IStrategoInt) resultID);
				mapping.put(resultID, factory.makeList(results));
			}

			printMapping(mapping);
			System.out.println("Inserting into combinator " + instruction);
			instructions.add(insertResults(context, insertResults, instruction, mapping));
		}

		return instructions;
	}
	
	private void printMapping(StrategoHashMap mapping) {
		for(Entry<IStrategoTerm, IStrategoTerm> entry : mapping.entrySet())
			System.out.println(entry.getKey() + " -> " + entry.getValue());
	}

	private IStrategoTerm insertResults(Context context, Strategy insertResults, IStrategoTerm instruction,
		StrategoHashMap resultCombinations) {
		return insertResults.invoke(context, instruction, resultCombinations);
	}

	private IStrategoTerm solve(Context context, Strategy performInstruction, IStrategoInt taskID,
		IStrategoTerm instruction) {
		return performInstruction.invoke(context, instruction, taskID);
	}

	private enum ResultType {
		Unknown, DynamicDependency, Fail, Success
	}

	private ResultType handleResult(IStrategoInt taskID, final IStrategoTerm instruction, final IStrategoTerm result) {
		if(result != null) {
			if(Tools.isTermAppl(result)) {
				final IStrategoAppl resultAppl = (IStrategoAppl) result;
				if(resultAppl.getConstructor().equals(dependencyConstructor)) {
					// The task has dynamic dependencies.
					updateDelayedDependencies(taskID, (IStrategoList) resultAppl.getSubterm(0));
					return ResultType.DynamicDependency;
				} else if(resultAppl.getConstructor().equals(singleConstructor)) {
					// The result must be treated as a single result.
					taskEngine.addResult(taskID, result.getSubterm(0));
					return ResultType.Success;
				}
			} else if (Tools.isTermList(result)) {
				// The task produced multiple results.
				for(IStrategoTerm resultsChild : result)
					taskEngine.addResult(taskID, resultsChild);
				return ResultType.Success;
			} else {
				// The task produced a single result.
				taskEngine.addResult(taskID, result);
				return ResultType.Success;
			}
		} else {
			// The task failed to produce a result.
			return ResultType.Fail;
		}
		return ResultType.Unknown;
	}

	private void tryScheduleNewTasks(IStrategoInt solved) {
		// Retrieve dependent tasks of the solved task.
		final Collection<IStrategoInt> dependents = taskEngine.getDependent(solved);
		// Make a copy for toRuntimeDependency because a remove operation can occur while iterating.
		final Collection<IStrategoInt> runtimeDependents =
			new ArrayList<IStrategoInt>(toRuntimeDependency.getInverse(solved));

		for(final IStrategoInt dependent : Iterables.concat(dependents, runtimeDependents)) {
			// Retrieve dependencies for a dependent task.
			Collection<IStrategoInt> dependencies = toRuntimeDependency.get(dependent);
			int dependenciesSize = dependencies.size();
			if(dependenciesSize == 0) {
				// If toRuntimeDependency does not contain dependencies for dependent yet, add them.
				dependencies = taskEngine.getDependencies(dependent);
				dependenciesSize = dependencies.size();
				toRuntimeDependency.putAll(dependent, dependencies);
			}

			// Remove the dependency to the solved task. If that was the last dependency, schedule the task.
			final boolean removed = toRuntimeDependency.remove(dependent, solved);
			if(dependenciesSize == 1 && removed)
				queue(dependent);
		}
	}

	private void updateDelayedDependencies(IStrategoInt delayed, IStrategoList dependencies) {
		// Sets the runtime dependencies for a task to the given dependency list.
		toRuntimeDependency.removeAll(delayed);
		for(final IStrategoTerm dependency : dependencies)
			toRuntimeDependency.put(delayed, (IStrategoInt) dependency);
	}

	private boolean isTaskCombinator(IStrategoTerm instruction) {
		// TODO: extendible task combinators; use new-combinator instead of new-task and set a boolean?
		if(Tools.isTermAppl(instruction)) {
			final String name = ((IStrategoAppl) instruction).getConstructor().getName();
			return name.equals("Choice") || name.equals("PropConstraint");
		}
		return false;
	}
}
