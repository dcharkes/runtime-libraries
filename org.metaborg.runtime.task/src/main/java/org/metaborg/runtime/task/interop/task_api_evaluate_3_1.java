package org.metaborg.runtime.task.interop;

import org.metaborg.runtime.task.TaskManager;
import org.spoofax.interpreter.terms.IStrategoList;
import org.spoofax.interpreter.terms.IStrategoTerm;
import org.strategoxt.lang.Context;
import org.strategoxt.lang.Strategy;

public class task_api_evaluate_3_1 extends Strategy {
	public static task_api_evaluate_3_1 instance = new task_api_evaluate_3_1();

	@Override
	public IStrategoTerm invoke(Context context, IStrategoTerm current, Strategy collectResults,
		Strategy insertResults, Strategy performInstruction, IStrategoTerm changedReads) {
		return TaskManager.getInstance().getCurrent()
			.evaluate(context, collectResults, insertResults, performInstruction, (IStrategoList) changedReads);
	}
}
