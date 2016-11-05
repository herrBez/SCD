import akka.actor._
import akka.actor.Actor._
import scala.collection.mutable.Queue
import scala.concurrent._
import scala.concurrent.duration._
import scala.util._
import java.util.concurrent.TimeUnit
import ExecutionContext.Implicits.global

/* Train -> Station */
case object Load
case class Unload(capacity : Integer)

/* Station -> Train ('Callbacks')*/
case class Unloaded(howmany : Integer) //used in order to say to the train that all customers are off of the train
case class Loaded(howmany : Integer)

/* Passenger -> Station */
case class Travel(who: ActorRef, to : ActorRef)

/* Station -> Passenger ('Callbacks')*/
case object Ready2Work

/* Passenger <-> Passenger (in order to simulate a working/rest time)*/
case object Finished

/* Train <-> Train (in order to simulate the traveling time) */
case object NextStop

/* Station A -> Station B (in order to enqueue a passenger from S_A to S_B) */
case class Send2Destination(who : ActorRef)


/**
  * Train Actor
  */
class Train(MAX_CAPACITY : Integer, station : Array[ActorRef]) extends Actor {

  var actualCapacity : Integer = MAX_CAPACITY;
  var nextStop : Integer = 0; // index of the next stop
  val actorName = "[" + self.path.name + "]: "
  // Interleaving time between different station
  val duration = Duration.create(1500, TimeUnit.MILLISECONDS);

  // Wait a second and arrive to the next stop
  context.system.scheduler.scheduleOnce(duration, self, NextStop)
 

  def receive = {
    case NextStop =>
      println(actorName + " Next stop is " + station(nextStop).path.name)
      station(nextStop) ! Unload(actualCapacity)   

    case Unloaded(howmany : Integer) =>
      actualCapacity += howmany
      println(actorName + " " + station(nextStop).path.name + " Passengers are off ");
      station(nextStop) ! Load
      

    case Loaded(howmany : Integer) =>
      println(actorName + " " + sender.path.name + " loaded " + howmany)
      actualCapacity -= howmany
      nextStop = (nextStop + 1) % station.length
      //"Sleep" a little bit \equiv ignore all message till the duration is over
      context.system.scheduler.scheduleOnce(duration, self, NextStop)

    case _ => println("Train: Unrecognized Pattern");
  }
}

/**
  * Station Actor
  */
class Station extends Actor {
  
  var qStart = Queue[(ActorRef, ActorRef)]()
  var qEnd = Queue[ActorRef]()
  var knownCapacity = 0;
  val actorName = "[" + self.path.name + "]: "
  


  def receive = {

    case Unload(capacity : Integer) =>
      knownCapacity = capacity
      println(actorName + " Capacity = " + knownCapacity + " |qStart| = " + qStart.length + " |qEnd| = " + qEnd.length)
      val howmany = qEnd.length
      //Notify the train
      while(!qEnd.isEmpty) {
        
        val p = qEnd.dequeue
        p ! Ready2Work
        knownCapacity += 1
      }
      println(actorName + " After the passengers are off. The train's capacity is " + knownCapacity)
      sender ! Unloaded(howmany)

    case Load =>
      var sent : Integer = 0
     
      while(sent < knownCapacity && !qStart.isEmpty){
        val p = qStart.dequeue
        p._2 ! Send2Destination(p._1)
        println(actorName + " " + p._1.path.name + " 'sent to " + p._2.path.name + "'s qEnd'");
        sent = sent+1
      }
      sender ! Loaded(sent)

      

    case Send2Destination(who : ActorRef) =>
      println(actorName + " enqueue " + who.path.name)
      qEnd.enqueue(who)
      

    case Travel(who: ActorRef, to : ActorRef) =>
      println("[" + who.path.name + "]: will travel from " + self.path.name + " to " + to.path.name)
      qStart.enqueue((who, to));


    case _ => println("Station: Unrecognized Pattern");
  }
}

/**
  * Passenger Actor 
  */
class Passenger(src : ActorRef, dest : ActorRef) extends Actor{
  var from : ActorRef  = src
  var to : ActorRef  = dest
  val workOrRestTime : Integer = 2
  val actorName = "[" + self.path.name + "]: "

  println(actorName + " (" + from.path.name + "," + to.path.name + ") created");

  from ! Travel(self, to)

  def switch = {
    val tmp : ActorRef = from
    from = to
    to = tmp
  }

  def receive = {
    case Ready2Work =>
      switch
      println(actorName + "Going to Work. What a nice day") 
      val duration = Duration.create(workOrRestTime * 1000, TimeUnit.MILLISECONDS);
      //"Sleep" a little bit \equiv ignore all message till the duration is over
      context.system.scheduler.scheduleOnce(duration, self, Finished)
    case Finished => 
      println(actorName + " I will go from " + from.path.name + " to " + to.path.name)
      //Do some job here a.k.a. sleep
      from ! Travel(self, to)

    case _ => println("Passenger: Unrecognized Pattern");
  }
}

object Main extends App{
  /* Reading eventual command line parameters */
  def getParam(param : String, default : Integer) : Integer = {
    var res : Integer = default
    if(param != null)
      res = param.toInt
    return res
  }


  val NUM_STATION : Integer = getParam(System.getProperty("NUM_STATION"), 5)
  val NUM_PASSENGER : Integer = getParam(System.getProperty("NUM_PASSENGER"), 10)
  val MAX_CAPACITY : Integer = getParam(System.getProperty("MAX_CAPACITY"), 3)

 


  /* Creating the actor system */
  val system = ActorSystem();

  val r = scala.util.Random
  val stationList : Array[ActorRef] = new Array[ActorRef](NUM_STATION);

  /* Initializing the train stations */
  for(i <- 0 to NUM_STATION - 1){
    stationList(i) = system.actorOf(Props[Station], name = "S" + (i+1))
  }

  system.actorOf(Props(new Train(MAX_CAPACITY, stationList)), name = "Train")

  for(i <- 1 to NUM_PASSENGER){
    var f : Integer = r.nextInt(NUM_STATION) // f = a random value [0, NUM_PASSENGERS)
    var t : Integer = r.nextInt(NUM_STATION)
    if(t == f)
      t = (t + 1) % NUM_STATION //If they are equal pick the next station
    system.actorOf(Props(new Passenger(stationList(f), stationList(t))), name = "P" + (i))
  }

  /*system.actorOf(Props(new Passenger(s1, s2)), name = "P0")*/
}
