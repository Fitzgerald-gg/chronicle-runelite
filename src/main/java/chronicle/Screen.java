/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

abstract class Screen
{
	final ChroniclePanel ui;
	final Board board;
	final ChroniclePlugin plugin;
	final Period period;
	final LocalStore store;

	Screen(ChroniclePanel ui, Board board)
	{
		this.ui = ui;
		this.board = board;
		this.plugin = board.plugin;
		this.period = board.period;
		this.store = board.store;
	}
}
