package gol

import (
	"fmt"
	"net/rpc"
	"time"
	"uk.ac.bris.cs/gameoflife/stubs"
	"uk.ac.bris.cs/gameoflife/util"
)

// distributorChannels holds all the channels used by the distributor.
type distributorChannels struct {
	events     chan<- Event
	ioCommand  chan<- ioCommand
	ioIdle     <-chan bool
	ioFilename chan<- string
	ioOutput   chan<- uint8
	ioInput    <-chan uint8
}

// distributor manages communication with the Broker and handles events and control commands.
func distributor(p Params, c distributorChannels, keyPresses <-chan rune) {
	brokerAddress := "3.211.249.206:8090"
	// make RPC connection with the Broker
	brokerClient, err := rpc.Dial("tcp", brokerAddress)
	if err != nil {
		fmt.Println("rpc error connecting to broker", err)
		return
	}
	defer brokerClient.Close()

	fmt.Println("distributor connected to broker at", brokerAddress)

	// read PGM img using IO CHANNEL
	c.ioCommand <- ioInput
	filename := fmt.Sprintf("%dx%d", p.ImageWidth, p.ImageHeight) //width x height lets say 512x512
	c.ioFilename <- filename                                      // read filename in io

	// initialize the world
	world := make([][]byte, p.ImageHeight)
	for i := range world {
		world[i] = make([]byte, p.ImageWidth)
	}

	// populate the world using ioInput
	for y := 0; y < p.ImageHeight; y++ {
		for x := 0; x < p.ImageWidth; x++ {
			world[y][x] = <-c.ioInput
			if world[y][x] == 255 {
				// cellFlipped event for initially alive cells
				c.events <- CellFlipped{0, util.Cell{X: x, Y: y}}
			}
		}
	}
	c.events <- TurnComplete{0}

	// StateChange event -> game starts
	c.events <- StateChange{0, Executing}

	// Output the initial world state when p.Turns == 0
	if p.Turns == 0 {
		// Output the initial world state via IO channels
		c.ioCommand <- ioOutput                                               // Command to write a PGM image
		outputFilename := fmt.Sprintf("%dx%dx0", p.ImageWidth, p.ImageHeight) //initial
		c.ioFilename <- outputFilename                                        // Specify the output filename

		// Send the world data via c.ioOutput channel
		for y := 0; y < p.ImageHeight; y++ {
			for x := 0; x < p.ImageWidth; x++ {
				c.ioOutput <- world[y][x]
			}
		}

		// Wait for IO to be idle
		c.ioCommand <- ioCheckIdle
		<-c.ioIdle
		c.events <- ImageOutputComplete{0, outputFilename}

		aliveCells := calculateAliveCells(p, world)
		c.events <- FinalTurnComplete{0, aliveCells}
		// Close events channel
		c.events <- StateChange{0, Quitting}

		close(c.events)
		return
	}
	//start game simulation
	ticker := time.NewTicker(2 * time.Second)
	defer ticker.Stop()
	paused := false
	turn := 0

simulationLoop:
	for turn < p.Turns {
		if !paused {
			// request/response BROKER
			turnRequest := stubs.TurnRequest{
				World:  world,
				Params: stubs.Params(p),
			}
			var turnResponse stubs.TurnResponse

			// Call Broker.GenerateTurn via RPC
			err := brokerClient.Call(stubs.GenerateTurn, turnRequest, &turnResponse)
			if err != nil {
				break
			}
			if turnResponse.Complete {
				newWorld := turnResponse.World
				// take cells that have changed state
				var cellsFlipped []util.Cell
				for y := 0; y < p.ImageHeight; y++ {
					for x := 0; x < p.ImageWidth; x++ {
						if newWorld[y][x] != world[y][x] {
							cellsFlipped = append(cellsFlipped, util.Cell{X: x, Y: y})
						}
					}
				}
				if len(cellsFlipped) > 0 {
					c.events <- CellsFlipped{turn + 1, cellsFlipped}
				}

				world = newWorld               // Update the world
				turn++                         // count increment
				c.events <- TurnComplete{turn} // event complete

			}
		}
	keyboard:
		for {
			select {
			case key := <-keyPresses:
				switch key {
				case 's':
					// Save current world state
					outputFilename := fmt.Sprintf("%dx%dx%d", p.ImageWidth, p.ImageHeight, turn)
					saveBoard(p, c, world, turn, outputFilename)
				case 'p':
					// Pause execution
					paused = !paused
					var newState State
					if paused {
						newState = Paused
					} else {
						newState = Executing
					}
					c.events <- StateChange{turn, newState}
				case 'q':
					// Handle quit signal
					fmt.Println("q' is pressed while paused, quiting simulation.")
					c.events <- StateChange{turn, Quitting}
					break simulationLoop
				case 'k':
					// Handle shutdown signal
					fmt.Println("'k' is pressed, terminate simulation and shutdown broker and workers.")
					// Save current world state
					c.ioCommand <- ioOutput
					outputFilename := fmt.Sprintf("%dx%dx%d", p.ImageWidth, p.ImageHeight, turn)
					c.ioFilename <- outputFilename
					for y := 0; y < p.ImageHeight; y++ {
						for x := 0; x < p.ImageWidth; x++ {
							c.ioOutput <- world[y][x]
						}
					}
					c.ioCommand <- ioCheckIdle
					<-c.ioIdle
					c.events <- ImageOutputComplete{turn, outputFilename}
					// Send close signal to Broker
					var brokerResponse stubs.BrokerResponse
					brokerRequest := stubs.BrokerRequest{}
					err := brokerClient.Call(stubs.CloseBroker, brokerRequest, &brokerResponse)
					if err != nil {
						fmt.Println("Error calling Broker.Close:", err)
					}
					c.events <- StateChange{turn, Quitting}
					break simulationLoop
				default:
					continue
				}
			default:
				break keyboard
			}
		}
		// Handle ticker events
		select {
		case <-ticker.C:
			// Request alive cell count from Broker
			var aliveResponse stubs.AliveCellsResponse
			aliveRequest := stubs.AliveCellsRequest{World: world, Params: stubs.Params(p)}
			err := brokerClient.Call(stubs.AliveCells, aliveRequest, &aliveResponse)
			if err != nil {
				fmt.Println("ERROR CALL BROKER AliveCells:", err)
			} else {
				c.events <- AliveCellsCount{turn, aliveResponse.AliveCount}
			}
		default:
		}
	}
	// finalize the simulation
	aliveCells := calculateAliveCells(p, world)
	c.events <- FinalTurnComplete{turn, aliveCells}

	// OUTPUT the final world state via IO channels
	c.ioCommand <- ioOutput // write a PGM image
	outputFilename := fmt.Sprintf("%dx%dx%d", p.ImageWidth, p.ImageHeight, turn)
	c.ioFilename <- outputFilename // OUTPUT FILENAME

	// send the world data via ioOutput chan
	for y := 0; y < p.ImageHeight; y++ {
		for x := 0; x < p.ImageWidth; x++ {
			c.ioOutput <- world[y][x]
		}
	}
	// io to be idle
	c.ioCommand <- ioCheckIdle
	<-c.ioIdle

	c.events <- ImageOutputComplete{turn, outputFilename} // take completed img output
	// quitting
	c.events <- StateChange{turn, Quitting}

	// close the events channel
	close(c.events)
}

//HELPER FUNCTIONS

// returns the list of alive cells in the current world state.
func calculateAliveCells(p Params, world [][]byte) []util.Cell {
	var cells []util.Cell
	for y, row := range world {
		for x, element := range row {
			if element == 255 {
				cells = append(cells, util.Cell{X: x, Y: y})
			}
		}
	}
	return cells
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
