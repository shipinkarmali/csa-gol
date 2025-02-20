package uk.ac.bris.cs.scotlandyard.model;

import com.google.common.collect.ImmutableList;
import javax.annotation.Nonnull;

import com.google.common.collect.ImmutableSet;
import uk.ac.bris.cs.scotlandyard.model.ScotlandYard.Factory;

import java.util.ArrayList;

/**
 * cw-model
 * Stage 2: Completbe this class
 */
public final class MyModelFactory implements Factory<Model> {
	@Nonnull
	@Override
	public Model build(GameSetup setup,
					   Player mrX,
					   ImmutableList<Player> detectives) {
		// Initialize the game state with the provided setup, Mr. X, and detectives
		ArrayList<Board.GameState> initialStates = new ArrayList<>();
		initialStates.add(new MyGameStateFactory().build(setup, mrX, detectives));
		return new MyModel(new ArrayList<>(), initialStates);
	}

	public final static class MyModel implements Model {

		private final ArrayList<Observer> observers;
		private final ArrayList<Board.GameState> gameStates;

		private MyModel(ArrayList<Observer> observers,
						ArrayList<Board.GameState> gameStates) {
			this.observers = observers;
			this.gameStates = gameStates;
		}

		@Nonnull
		@Override
		public Board getCurrentBoard() {
			if (gameStates.isEmpty()) {
				throw new IllegalStateException("There are no game states available.");
			}
			return gameStates.get(gameStates.size() - 1);
		}

		@Override
		public void registerObserver(@Nonnull Observer observer) {
			if (observer == null) throw new NullPointerException("Observer cannot be null.");
			if(observers.contains(observer)) throw new IllegalArgumentException("Observer already registered.");
			observers.add(observer);
		}

		@Override
		public void unregisterObserver(@Nonnull Observer observer) {
			if(observer == null) throw new NullPointerException("Observer cannot be null.");
			if(!observers.contains(observer)) throw new IllegalArgumentException("Observer was not registered.");
			observers.remove(observer);
		}

		@Nonnull
		@Override
		public ImmutableSet<Observer> getObservers() {
			return ImmutableSet.copyOf(observers);
		}

		@Override
		public void chooseMove(@Nonnull Move move) {
			// getCurrentBoard() returns a Board that can be cast to GameStates
			Board.GameState currentState = (Board.GameState) getCurrentBoard();
			Board.GameState newState = currentState.advance(move);

			// Update gameStates after a move
			gameStates.add(newState);

			// Determine the event type based on whether the game is over
			Observer.Event event = newState.getWinner().isEmpty() ? Observer.Event.MOVE_MADE : Observer.Event.GAME_OVER;

			// Update the observers - move made/game over
			observers.forEach(observer -> observer.onModelChanged(newState, event));
		}
	}
}
