package org.thp.thehive.services

import scala.util.Try

import play.api.libs.json.JsObject

import gremlin.scala._
import javax.inject.{Inject, Singleton}
import org.thp.scalligraph.EntitySteps
import org.thp.scalligraph.auth.AuthContext
import org.thp.scalligraph.models._
import org.thp.scalligraph.services._
import org.thp.thehive.models._

@Singleton
class AuditSrv @Inject()(
    userSrv: UserSrv,
    eventSrv: EventSrv
)(implicit db: Database, schema: Schema)
    extends VertexSrv[Audit, AuditSteps] {
  val auditUserSrv    = new EdgeSrv[AuditUser, Audit, User]
  val auditedSrv      = new EdgeSrv[Audited, Audit, Product]
  val auditContextSrv = new EdgeSrv[AuditContext, Audit, Product]

  override def steps(raw: GremlinScala[Vertex])(implicit graph: Graph): AuditSteps = new AuditSteps(raw)

  def getObject(audit: Audit with Entity)(implicit graph: Graph): Option[Entity] =
    get(audit).getObject

  def create(
      audit: Audit,
      context: Entity,
      `object`: Option[Entity]
  )(
      implicit graph: Graph,
      authContext: AuthContext
  ): Try[RichAudit] =
    userSrv.current.getOrFail().map { user ⇒
      val createdAudit = create(audit)
      auditUserSrv.create(AuditUser(), createdAudit, user)
      auditContextSrv.create(AuditContext(), createdAudit, context)
      `object`.map(auditedSrv.create(Audited(), createdAudit, _))
      val richAudit = RichAudit(createdAudit, context, `object`)
      db.onSuccessTransaction(graph)(() ⇒ eventSrv.publish(richAudit))
      richAudit
    }

  def createCase(`case`: Case with Entity)(implicit graph: Graph, authContext: AuthContext): Try[RichAudit] =
    create(Audit("createCase", `case`), `case`, Some(`case`))

  def updateCase(`case`: Case with Entity, details: JsObject)(implicit graph: Graph, authContext: AuthContext): Try[RichAudit] =
    create(Audit("updateCase", `case`, Some(details.toString)), `case`, Some(`case`))

  def createAlert(alert: Alert with Entity)(implicit graph: Graph, authContext: AuthContext): Try[RichAudit] =
    create(Audit("createAlert", alert), alert, Some(alert))

  def updateAlert(alert: Alert with Entity, details: JsObject)(implicit graph: Graph, authContext: AuthContext): Try[RichAudit] =
    create(Audit("updateAlert", alert, Some(details.toString)), alert, Some(alert))

  def createCaseTemplate(caseTemplate: CaseTemplate with Entity)(implicit graph: Graph, authContext: AuthContext): Try[RichAudit] =
    create(Audit("createCaseTemplate", caseTemplate), caseTemplate, Some(caseTemplate))

  def createLog(log: Log with Entity, task: Task with Entity)(implicit graph: Graph, authContext: AuthContext): Try[RichAudit] =
    create(Audit("createLog", log), task, Some(log))

  def updateLog(log: Log with Entity, details: JsObject)(implicit graph: Graph, authContext: AuthContext): Try[RichAudit] =
    // FIXME find the related task and use it as context
    create(Audit("updateLog", log, Some(details.toString)), log, Some(log))

  def createTask(task: Task with Entity, `case`: Case with Entity)(implicit graph: Graph, authContext: AuthContext): Try[RichAudit] =
    create(Audit("createTask", task), `case`, Some(task))
}

@EntitySteps[Audit]
class AuditSteps(raw: GremlinScala[Vertex])(implicit db: Database, schema: Schema, graph: Graph) extends BaseVertexSteps[Audit, AuditSteps](raw) {

  override def newInstance(raw: GremlinScala[Vertex]): AuditSteps = new AuditSteps(raw)

  def richAudit: ScalarSteps[RichAudit] =
    ScalarSteps(
      raw
        .project(
          _.apply(By[Vertex]())
            .and(By(__[Vertex].outTo[AuditContext]))
            .and(By(__[Vertex].outTo[Audited].fold()))
        )
        .map {
          case (audit, context, obj) ⇒
            RichAudit(audit.as[Audit], context.asEntity, atMostOneOf[Vertex](obj).map(_.asEntity))
        }
    )

  def richAuditWithCustomObjectRenderer(
      entityToJson: String ⇒ GremlinScala[Vertex] ⇒ GremlinScala[JsObject]
  ): ScalarSteps[(RichAudit, JsObject)] =
    ScalarSteps(
      raw
        .project(
          _.apply(By[Vertex]())
            .and(By(__[Vertex].outTo[AuditContext]))
            .and(By(__[Vertex].outTo[Audited].fold()))
            .and(
              By(
                __[Vertex]
                  .outTo[Audited]
                  .choose[Label, JsObject](
                    on = _.label(),
                    BranchCase("Case", entityToJson("Case")),
                    BranchCase("Task", entityToJson("Task")),
                    BranchCase("Log", entityToJson("Log"))
                  )
              )
            )
        )
        .map {
          case (audit, context, obj, objectJson) ⇒
            RichAudit(audit.as[Audit], context.asEntity, atMostOneOf[Vertex](obj).map(_.asEntity)) → objectJson
        }
    )

  def forContext(contextId: String): AuditSteps = newInstance(raw.filter(_.outTo[AuditContext].has(Key("_id") of contextId)))

  def visible(implicit authContext: AuthContext): AuditSteps = newInstance(
    raw.filter(
      _.outTo[AuditContext]
        .coalesce(                                   // find organisation
          _.inTo[ShareCase].inTo[OrganisationShare], // case
          _.inTo[ShareTask].inTo[OrganisationShare], // task
          _.inTo[ShareObservable].inTo[OrganisationShare],
          _.outTo[AlertOrganisation]
        )
        .inTo[RoleOrganisation]
        .inTo[UserRole]
        .has(Key("login") of authContext.userId)
    )
  )

  def getObject: Option[Entity] =
    raw.outTo[Audited].headOption().flatMap { v ⇒
      schema.getModel(v.label()).collect {
        case model: VertexModel ⇒
          model.converter(db, graph).toDomain(v)
      }
    }
}