// worker.go
package main

import (
	"flag"
	"fmt"
	"log"
	"net"
	"net/rpc"
	"time"
	"uk.ac.bris.cs/gameoflife/stubs"
)

// Worker struct holds channels for managing worker state.
type Worker struct {
	closeListener chan bool
}

// GeneratePart processes a part of the world and returns the updated segment.
func (w *Worker) GeneratePart(req stubs.WorkerRequest, res *stubs.WorkerResponse) error {
	world := req.World
	x1 := req.X1
	x2 := req.X2
	y1 := req.Y1
	y2 := req.Y2
	params := req.Params

	heightX := params.ImageHeight
	width := params.ImageWidth
	// Initialize the nextWorldPart matrix
	x := x2 - x1
	y := y2 - y1
	partWorld := make([][]byte, x)
	for i := range partWorld {
		partWorld[i] = make([]byte, y)
	}
	// game rules
	for i := x1; i < x2; i++ {
		for j := y1; j < y2; j++ {
			sum := 0
			ranger := []int{-1, 0, 1}
			for _, dy := range ranger {
				for _, dx := range ranger {
					if dy == 0 && dx == 0 {
						continue
					} // Skip
					ny := (i + dy + heightX) % heightX
					nx := (j + dx + width) % width
					if world[ny][nx] == 255 {
						sum++
					}
				}
			}
			if world[i][j] == 255 {
				if sum < 2 || sum > 3 {
					partWorld[i-x1][j-y1] = 0
				} else {
					partWorld[i-x1][j-y1] = 255
				}
			} else {
				if sum == 3 {
					partWorld[i-x1][j-y1] = 255
				} else {
					partWorld[i-x1][j-y1] = world[i][j]
				}
			}
		}
	}

	res.WorldPart = partWorld
	res.Complete = true
	// checking if the world is separated alright
	// fmt.Printf("worker on part: rows %d to %d\n", x1, x2)
	return nil
}

// Close IS FUNCTION THAT SHUTDOWN WORKERS WHEN PRESSED K during simulation
func (w *Worker) Close(required stubs.WorkerRequest, res *stubs.WorkerResponse) error {
	w.closeListener <- true
	return nil
}

func main() {
	//AWS PORTS
	portPtr := flag.String("port", "9000", "Port for the Worker to listen on")
	flag.Parse()

	// Initialize the Worker with a closeListener channel
	closeListener := make(chan bool)
	worker := &Worker{closeListener: closeListener}

	// Register the Worker with the RPC server
	err := rpc.Register(worker)
	if err != nil {
		log.Fatal("Error registering Worker:", err)
	}

	// Listen on the specified TCP port
	listener, err := net.Listen("tcp", ":"+*portPtr)
	if err != nil {
		log.Fatal("Listen error:", err)
	}
	defer listener.Close()
	fmt.Println("worker listening on port", *portPtr)

	go rpc.Accept(listener) // accept incoming RPC connections
	<-closeListener
	fmt.Println("WORKER SHUT DOWN!")
	time.Sleep(3 * time.Second) // TRY PREVENT ERROR
}
