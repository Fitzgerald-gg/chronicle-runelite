/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

abstract class Screen
{
	ChroniclePanel ui;
	Board board;
	ChroniclePlugin plugin;
	Period period;
	LocalStore store;
}
