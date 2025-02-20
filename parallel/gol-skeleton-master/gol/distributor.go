package gol

import (
	"fmt"
	"time"
	"uk.ac.bris.cs/gameoflife/util"
)

type distributorChannels struct {
	events     chan<- Event
	ioCommand  chan<- ioCommand
	ioIdle     <-chan bool
	ioFilename chan<- string
	ioOutput   chan<- uint8
	ioInput    <-chan uint8
}

type workerChannels struct {
	input        chan [][]byte
	output       chan [][]byte
	flippedCells chan []util.Cell
}

type worldSnap struct {
	world [][]byte
	turn  int
}

// Distributor divides the work between workers and interacts with other goroutines.
func distributor(p Params, c distributorChannels, keyPresses <-chan rune) {
	// TODO: Create a 2D slice to store the world.

	world := make([][]byte, p.ImageHeight)
	for i := range world {
		world[i] = make([]byte, p.ImageWidth)
	}

	// request IO to read the input file
	c.ioCommand <- ioInput
	c.ioFilename <- fmt.Sprintf("%dx%d", p.ImageWidth, p.ImageHeight)

	// receive the initial world state from IO
	for y := 0; y < p.ImageHeight; y++ {
		for x := 0; x < p.ImageWidth; x++ {
			world[y][x] = <-c.ioInput
		}
	}
	// collect initial alive cells
	var initialAliveCells []util.Cell
	for y := 0; y < p.ImageHeight; y++ {
		for x := 0; x < p.ImageWidth; x++ {
			if world[y][x] == 255 {
				initialAliveCells = append(initialAliveCells, util.Cell{X: x, Y: y})
			}
		}
	}
	// send CellsFlipped event for initial alive cells
	if len(initialAliveCells) > 0 {
		c.events <- CellsFlipped{0, initialAliveCells}
	}

	// send initial StateChange event
	c.events <- StateChange{0, Executing}

	// determine the number of workers and how to split the world
	numWorkers := p.Threads
	if numWorkers < 1 {
		numWorkers = 1
	}
	if numWorkers > p.ImageHeight {
		numWorkers = p.ImageHeight
	}
	rowsPerWorker := p.ImageHeight / numWorkers
	remainder := p.ImageHeight % numWorkers

	// Initialize worker channels
	workerChans := make([]workerChannels, numWorkers)
	for i := 0; i < numWorkers; i++ {
		workerChans[i] = workerChannels{
			input:        make(chan [][]byte),
			output:       make(chan [][]byte),
			flippedCells: make(chan []util.Cell),
		}
	}

	// Start workers
	for i := 0; i < numWorkers; i++ {
		go startWorker(p, workerChans[i])
	}

	// Declare variables to track state
	turn := 0
	paused := false
	tickerQuit := make(chan bool)
	worldSnaps := make(chan worldSnap)

	// Start the ticker goroutine
	go func() {
		ticker := time.NewTicker(2 * time.Second)
		defer ticker.Stop()
		var currWorld [][]byte
		var currTurn int
		for {
			select {
			case <-ticker.C:
				if currWorld != nil {
					aliveCount := countAliveCells(currWorld)
					c.events <- AliveCellsCount{currTurn, aliveCount}
				}
			case snapshot := <-worldSnaps:
				currWorld = snapshot.world
				currTurn = snapshot.turn
			case <-tickerQuit:
				return
			}
		}
	}()
	// TODO: Execute all turns of the Game of Life.

simulationLoop: // loop
	for turn < p.Turns {
		select {
		case key := <-keyPresses:
			switch key {
			case 's':
				filename := fmt.Sprintf("%dx%dx%d", p.ImageWidth, p.ImageHeight, turn)
				saveBoard(p, c, world, turn, filename)
			case 'q':
				fmt.Println("q' is pressed while paused, quiting simulation.")
				c.events <- StateChange{CompletedTurns: turn, NewState: Quitting}
				break simulationLoop
			case 'p':
				paused = !paused
				var newState State
				if paused {
					newState = Paused
				} else {
					newState = Executing
				}
				c.events <- StateChange{turn, newState}
			}
		default:
			if !paused {
				// execute one turn of the Game of Life, distribute work to workers
				y1 := 0
				for i := 0; i < numWorkers; i++ {
					// calculate the number of rows for this worker
					workerRows := rowsPerWorker
					if i < remainder {
						workerRows++
					}
					y2 := y1 + workerRows
					// include boundary rows for wrapping
					segment := make([][]byte, workerRows+2)
					for j := -1; j <= workerRows; j++ {
						rowIndex := (y1 + j + p.ImageHeight) % p.ImageHeight
						segment[j+1] = world[rowIndex]
					}
					// send segment to worker
					workerChans[i].input <- segment
					y1 = y2
				}
				// Collect results from workers
				newWorld := make([][]byte, p.ImageHeight)
				y1 = 0
				var allFlippedCells []util.Cell

				for i := 0; i < numWorkers; i++ {
					// Receive updated segment from worker
					updSegment := <-workerChans[i].output
					flippedCells := <-workerChans[i].flippedCells
					// calculate the number of rows for each worker
					workerRows := rowsPerWorker
					if i < remainder {
						workerRows++
					}
					y2 := y1 + workerRows
					// copy the updated segment into the new world
					copy(newWorld[y1:y2], updSegment)
					// adjusting flipped cell positions
					for _, cell := range flippedCells {
						cell.Y += y1
						allFlippedCells = append(allFlippedCells, cell)
					}
					y1 = y2
				}
				// update the world with newW
				world = newWorld
				turn++
				// send CellsFlipped event if there are any flipped cells
				if len(allFlippedCells) > 0 {
					c.events <- CellsFlipped{turn, allFlippedCells}
				}
				// send TurnComplete event
				c.events <- TurnComplete{turn}
				// send a snapshot of the world to the ticker goroutine
				snapshot := make([][]byte, len(world))
				for i := range world {
					row := make([]byte, len(world[i]))
					copy(row, world[i])
					snapshot[i] = row
				}
				worldSnaps <- worldSnap{snapshot, turn}
			} else {
				// if paused, sleep briefly to avoid busy-waiting
				time.Sleep(100 * time.Millisecond)
			}
		}
	}
	// Signal the ticker goroutine to stop
	tickerQuit <- true
	// Close worker input channels to signal workers to exit
	for i := 0; i < numWorkers; i++ {
		close(workerChans[i].input)
	}
	// Save the final state
	filename := fmt.Sprintf("%dx%dx%d", p.ImageWidth, p.ImageHeight, turn)
	saveBoard(p, c, world, turn, filename)
	// Collect the list of alive cells
	var aliveCells []util.Cell
	for y := 0; y < p.ImageHeight; y++ {
		for x := 0; x < p.ImageWidth; x++ {
			if world[y][x] == 255 {
				aliveCells = append(aliveCells, util.Cell{X: x, Y: y})
			}
		}
	}

	// TODO: Report the final state using FinalTurnCompleteEvent.
	c.events <- FinalTurnComplete{turn, aliveCells}
	c.ioCommand <- ioCheckIdle
	<-c.ioIdle
	// quit
	c.events <- StateChange{turn, Quitting}
	//close event channel
	close(c.events)
}

//HELPER FUNCTIONS

// count alive neighbors in a segment with wrapping
func countAliveNextSegment(segment [][]byte, x, y int) int {
	alive := 0
	height := len(segment)
	width := len(segment[0])
	for dy := -1; dy <= 1; dy++ {
		for dx := -1; dx <= 1; dx++ {
			if dx == 0 && dy == 0 {
				continue // skip the current cell
			}
			nx := (x + dx + width) % width
			ny := (y + dy + height) % height
			if segment[ny][nx] == 255 {
				alive++
			}
		}
	}
	return alive
}

// compute the next state for a segment and collect flipped cells
func GeneratePartState(segment [][]byte, width int) ([][]byte, []util.Cell) {
	height := len(segment)
	newSegment := make([][]byte, height-2) // Exclude boundary rows
	var flippedCells []util.Cell

	for y := 1; y < height-1; y++ {
		rowUpd := make([]byte, width)
		for x := 0; x < width; x++ {
			aliveNeighbors := countAliveNextSegment(segment, x, y)
			nextState := segment[y][x]
			var newState byte
			if nextState == 255 {
				if aliveNeighbors < 2 || aliveNeighbors > 3 {
					newState = 0 // cell die
				} else {
					newState = 255 // cell stay alive
				}
			} else {
				if aliveNeighbors == 3 {
					newState = 255 // cell become alive
				} else {
					newState = 0 // cell stay dead
				}
			}
			rowUpd[x] = newState
			// If the cell state changed, record its position
			if newState != nextState {
				flippedCells = append(flippedCells, util.Cell{
					X: x,
					Y: y - 1, // Adjust y to exclude boundary
				})
			}
		}
		newSegment[y-1] = rowUpd
	}
	return newSegment, flippedCells
}

// worker function that processes a segment of the world
func startWorker(p Params, c workerChannels) {
	for {
		segment, ok := <-c.input
		if !ok {
			break // exit if closed
		}
		newSegment, flippedCells := GeneratePartState(segment, p.ImageWidth)
		c.output <- newSegment
		c.flippedCells <- flippedCells
	}
}

// count alive cells in the world
func countAliveCells(world [][]byte) int {
	aliveCounter := 0
	for y := 0; y < len(world); y++ {
		for x := 0; x < len(world[0]); x++ {
			if world[y][x] == 255 {
				aliveCounter++
			}
		}
	}
	return aliveCounter
}

// Function to save the board to a PGM file
func saveBoard(p Params, c distributorChannels, world [][]byte, turn int, filename string) {
	// send ioOutput command and filename to IO goroutine
	c.ioCommand <- ioOutput
	c.ioFilename <- filename
	// send the board data to the IO goroutine
	for y := 0; y < p.ImageHeight; y++ {
		for x := 0; x < p.ImageWidth; x++ {
			c.ioOutput <- world[y][x]
		}
	}
	c.ioCommand <- ioCheckIdle // io goroutine has finished writing
	<-c.ioIdle
	c.events <- ImageOutputComplete{turn, filename} // send ImageOutputComplete event

}
