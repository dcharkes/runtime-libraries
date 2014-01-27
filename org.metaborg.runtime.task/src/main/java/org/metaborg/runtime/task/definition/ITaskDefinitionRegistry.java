package org.metaborg.runtime.task.definition;

import org.spoofax.interpreter.stratego.Strategy;

public interface ITaskDefinitionRegistry {
	public abstract ITaskDefinition register(String name, byte arity, Strategy strategy, boolean isCombinator,
		boolean shortCircuit);

	public abstract ITaskDefinition get(String name, byte arity);

	public abstract ITaskDefinition get(TaskDefinitionIdentifier identifier);

	public abstract void reset();
}