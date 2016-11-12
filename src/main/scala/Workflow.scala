package workflow

import scala.concurrent.Future
import cats.Functor
import cats.free.Free
import play.api.Logger
import play.api.mvc.{Call, Request, RequestHeader, Result, Session, WebSocket}


object Workflow {
  // TODO expect as implicit
  import play.api.libs.concurrent.Execution.Implicits._

  private val logger = Logger("application.workflow")

  private object ResultsImpl extends play.api.mvc.Results

  case class WorkflowConf[A](
    workflow:    Workflow[A],
    router:      {def post(stepKey: String): Call; def get(stepKey: String): Call}
  )

  case class WorkflowContext[A] private (
    actionCurrent:  Call,
    actionPrevious: Option[Call],
    stepObject:     Option[A],
    restart:        Call,
    goto:           String => Call // TODO can we avoid this? maybe return Option[Call] ?
  )

  /** A single step in a Workflow.
   *  @constructor create a new Step with get, post and optionally ws.
   *  @param get defines the steps initial loading. If returns None, then the initial request will go straight to {{{post}}}.
   *  @param post defines the steps processing. This may involve form validation, which may return a Result, if failed, or
   *              a value to be stored in the session, and made accessible to the following steps.
   *  @param ws optionally, steps may support WebSockets. The websockets will have access
   *            to the context, and any step data just as get and post. However it is expected
   *            that a post will be made to advance the workflow.
   */
 // TODO both get and ws are optional - conform the type?
  case class Step[A](
    get:  WorkflowContext[A] => Request[Any] => Future[Option[Result]],
    post: WorkflowContext[A] => Request[Any] => Future[Either[Result, A]],
    ws:   Option[WorkflowContext[A] => RequestHeader => WebSocket[String, String]] = None // TODO support any input, output (not just String) if a reader and writer are provided.
  )

  sealed trait WorkflowSyntax[+Next]
  private object WorkflowSyntax {
    case class WSStep[A, Next](label: String, step: Step[A], reader: upickle.default.Reader[A], writer: upickle.default.Writer[A], next: A => Next) extends WorkflowSyntax[Next]
  }

  implicit val workflowSyntaxFunctor: Functor[WorkflowSyntax] = new Functor[WorkflowSyntax] {
    def map[A, B](fa: WorkflowSyntax[A])(f: A => B): WorkflowSyntax[B] = fa match {
      case ws: WorkflowSyntax.WSStep[_, A] => WorkflowSyntax.WSStep(ws.label, ws.step, ws.reader, ws.writer, ws.next andThen f)
    }
  }

  /** Defines a sequence of steps to be executed */
  type Workflow[A] = Free[WorkflowSyntax, A]

  object Workflow extends cats.Monad[Workflow] {
    // workflow cannot be a monadPlus unless A is a Monoid...
  //object Workflow extends cats.MonadFilter[Workflow] {
    //def empty[A]: Workflow[A] = pure(A.empty) // Free.liftF(WorkflowSyntax.Nothing())

    override def flatMap[A, B](fa: Workflow[A])(f: A => Workflow[B]): Workflow[B] =
      fa.flatMap(f)

    @annotation.tailrec
    override def tailRecM[A, B](a: A)(f: A => Workflow[Either[A,B]]): Workflow[B] =
      f(a).go{
        case WorkflowSyntax.WSStep(label, step, reader, writer, next) => next(a)
      } match {
        case Left(a)  => tailRecM(a)(f)
        case Right(b) => pure(b)
      }

    override def pure[A](x: A): Workflow[A] =
      Free.pure(x)
  }

  /** Wraps a Step as a Workflow so can be composed in a Workflow
   *  @param label the name of the step. Is used in URLs to identify the current step,
   *         and as the key to the result when stored - needs to be unique in the workflow
   *  @param step the step to be included in the workflow
   *  @param reader defines how to read the step result out of the session
   *  @param writer defines how to write the step result to the session
   */
  def step[A](label: String, step: Step[A])(implicit reader: upickle.default.Reader[A], writer: upickle.default.Writer[A]): Workflow[A] = {
    Free.liftF(WorkflowSyntax.WSStep[A,A](label, step, reader, writer, identity))
  }

  private def mkWorkflowContext[A](wfc: WorkflowConf[A], label: String, previousLabel: Option[String], optB: Option[A]): WorkflowContext[A] = {
    val actionCurrent = wfc.router.post(label)
    WorkflowContext(
      actionCurrent  = actionCurrent,
      actionPrevious = previousLabel.map(wfc.router.post(_)),
      stepObject     = optB,
      restart        = wfc.router.get("start"),
      goto           = s => wfc.router.get(s)
    )
  }

  /** Will execute a workflow and return an Action result. The GET request will be
   *  directed to the indicated stepId.
   *
   *  @param wfc the configuration defining the workflow
   *  @param stepId the current step position. A stepId value of {{{"start"}}} will
             clear the session and redirect to the first stepId in the workflow.
   */
  def getWorkflow[A](wfc: WorkflowConf[A], stepId: String)(implicit request: Request[Any]): Future[Result] = {
    logger.warn(s"getWorkflow $stepId")
    if (stepId == "start") {
      val initialStep = nextLabel(wfc.workflow)
      Future(ResultsImpl.Redirect(wfc.router.get(initialStep).url, request.queryString).withNewSession)
    } else {
      doGet(wfc, stepId, None)(wfc.workflow)(request).flatMap {
        case Some(r) => Future(r)
        case None    => postWorkflow(wfc, stepId)(request)
      }
    }
  }

  private def doGet[A](wfc: WorkflowConf[A], targetLabel: String, previousLabel: Option[String])(wf: Workflow[A]): Request[Any] => Future[Option[Result]] =
    request => wf.fold(
      { a: A => sys.error("doGet: flow finished!") // flow has finished (only will happen if last step has a post)
      },
      {
        case ws: WorkflowSyntax.WSStep[A,_] =>
          logger.warn(s"doGet $targetLabel")
          if (ws.label == targetLabel) {
            val optB = optDataFor(ws.label, request.session)(ws.reader)
            val ctx = mkWorkflowContext(wfc, ws.label, previousLabel, optB)
            ws.step.get(ctx)(request)
          } else {
            val b = dataFor(ws.label, request.session)(ws.reader)
            doGet(wfc, targetLabel, Some(ws.label))(ws.next(b))(request)
          }
      }
    )

  /** Will execute a workflow and return an Action result. The POST request will be
   *  directed to the indicated stepId.
   *
   *  @param wfc the configuration defining the workflow
   *  @param stepId the current step position.
   */
  def postWorkflow[A](wfc: WorkflowConf[A], stepId: String)(implicit request: Request[Any]): Future[Result] = {
    logger.warn(s"postWorkflow $stepId")
    doPost(wfc, stepId, None)(wfc.workflow)(request)
  }

  private def doPost[A](wfc: WorkflowConf[A], targetLabel: String, previousLabel: Option[String])(wf: Workflow[A]): Request[Any] => Future[Result] =
    request => wf.fold(
      { a: A => sys.error("doPost: flow finished!") // flow has finished (only will happen if last step has a post)
      },
      {
        case ws: WorkflowSyntax.WSStep[A,_] =>
          logger.warn(s"doPost $targetLabel")
          if (ws.label == targetLabel) {
            val optB = optDataFor(ws.label, request.session)(ws.reader)
            val ctx = mkWorkflowContext(wfc, ws.label, previousLabel, optB)
            ws.step.post(ctx)(request).map {
              case Left(r)  => logger.warn(s"$ws.label returning result"); r
              case Right(a) => logger.warn(s"putting ${ws.label} -> $a in session")
                               val next = nextLabel(ws.next(a))
                               logger.warn(s"redirecting to $next")
                               ResultsImpl.Redirect(mkWorkflowContext(wfc, next, previousLabel, optB).actionCurrent).withSession(
                                 request.session + (ws.label -> upickle.default.write(a)(ws.writer)))
            }
          } else {
            val b = dataFor(ws.label, request.session)(ws.reader)
            doPost(wfc, targetLabel, Some(ws.label))(ws.next(b))(request)
          }
      }
    )

  private type WS[A,B] = scala.concurrent.Future[Either[play.api.mvc.Result,(play.api.libs.iteratee.Enumerator[A], play.api.libs.iteratee.Iteratee[B,Unit]) => Unit]]

  /** Will execute a workflow and return an Action result. The websocket request will be
   *  directed to the indicated stepId. A Runtime exception will be thrown if the Step does not
   *  support a websocket request.
   *
   *  @param wfc the configuration defining the workflow
   *  @param stepId the current step position.
   */
  def wsWorkflow[A](wfc: WorkflowConf[A], currentLabel: String): RequestHeader => WS[String, String] = request => {
    logger.warn(s"wsWorkflow $currentLabel")
    doWs(wfc, currentLabel, None)(wfc.workflow)(request) match {
      case WebSocket(f) => f(request)
    }
  }

  private def doWs[A](wfc: WorkflowConf[A], targetLabel: String, previousLabel: Option[String])(wf: Workflow[A]): RequestHeader => WebSocket[String, String] =
    request => wf.fold(
      { a: A => sys.error("doGet: flow finished!") // flow has finished (only will happen if last step has a post)
      },
      {
        case ws: WorkflowSyntax.WSStep[A,_] =>
          logger.warn(s"doWs $targetLabel")
          if (ws.label == targetLabel) {
            val ctx = mkWorkflowContext(wfc, ws.label, previousLabel, None)
            ws.step.ws.getOrElse(sys.error(s"No ws defined for step ${ws.label}"))(ctx)(request)
          } else {
            val b = dataFor(ws.label, request.session)(ws.reader)
            doWs(wfc, targetLabel, Some(ws.label))(ws.next(b))(request)
          }
      }
    )

  private def dataFor[A](label: String, session: Session)(implicit reader: upickle.default.Reader[A]): A =
    session.get(label) match {
      case Some(a) => upickle.default.read[A](a)
      case None    => sys.error(s"invalid state - should have stored result for step $label")
    }

  private def optDataFor[A](label: String, session: Session)(implicit reader: upickle.default.Reader[A]): Option[A] =
    session.get(label) match {
      case Some(a) => Some(upickle.default.read[A](a))
      case None    => None
    }

  private def nextLabel[A](wf: Workflow[A]) = wf.resume match {
    case Left(ws: WorkflowSyntax.WSStep[_, _]) => ws.label
    case err                                   => sys.error(s"no next label: $err")
  }
}
