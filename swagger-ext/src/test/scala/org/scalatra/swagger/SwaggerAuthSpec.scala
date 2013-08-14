package org.scalatra
package swagger

import org.scalatra.test.specs2.MutableScalatraSpec
import org.scalatra.json.NativeJsonSupport
import org.scalatra.auth.ScentrySupport
import org.json4s._
import org.json4s.native.JsonMethods._
import org.scalatra.auth.ScentryStrategy
import org.scalatra.servlet.ServletApiImplicits._
import util.RicherString._
import org.scalatra.auth.ScentryConfig
import org.scalatra.auth.ScentryAuthStore.CookieAuthStore
import javax.servlet.http.{HttpServletResponse, HttpServletRequest}

object SwaggerAuthSpec {
  case class User(login: String, token: String = "the_token")
  
  val Users = List(User("tom", "token1"), User("kate", "token2"), User("john"))
  
  class SpecSwagger extends SwaggerWithAuth("1.1", "1")
  
  class HeaderOrQueryToken(protected val app: ScalatraBase) extends ScentryStrategy[User] {
    override def name = "header_or_query_token"
    private def token(implicit request: HttpServletRequest) = (app.request.header("API-TOKEN") orElse app.params.get("api_token")).flatMap(_.blankOption)
    override def isValid(implicit request: HttpServletRequest) = token.isDefined
    def authenticate()(implicit request: HttpServletRequest, response: HttpServletResponse) = {
      token match {
        case Some("token1") => Option(Users(0))
        case Some("token2") => Option(Users(1))
        case Some("the_token") => Option(Users(2))
        case _ => None
      }
    }
  }
  
  trait AuthenticatedBase extends ScalatraServlet with NativeJsonSupport with ScentrySupport[User] {
    type ScentryConfiguration = ScentryConfig
    protected val scentryConfig: ScentryConfiguration = new ScentryConfig {}
    
    protected val fromSession: PartialFunction[String, User] = {
      case s: String => Users.find(_.login == s).get
    }
    protected val toSession: PartialFunction[User, String] = {
      case u: User => u.login
    }
    override def configureScentry = {
//      scentry.store = new CookieAuthStore(this)
    }
    override def registerAuthStrategies = {
      scentry.register(new HeaderOrQueryToken(this))
    }
    
    error {
      case t: Throwable => t.printStackTrace()
      case t => t.printStackTrace()
    }
  }
  
  class ResourcesApp(implicit protected val swagger: SwaggerWithAuth) 
  				extends AuthenticatedBase 
  				with NativeJsonSupport 
  				with CorsSupport
  				with SwaggerAuthBase[User] {
    error {
      case t: Throwable => t.printStackTrace()
    }
  }
  
  class PetsApi(implicit protected val swagger: SwaggerWithAuth) extends AuthenticatedBase with NativeJsonSupport with SwaggerAuthSupport[User] {

    implicit protected def jsonFormats: Formats = DefaultFormats

    protected val applicationDescription = "The pets api"
    override protected val applicationName = Some("pets")
    
    private def allowsTom(u: Option[User]) = {
      u.map(_.login) == Some("tom") 
    }
    
    private def allowsAuthenticated(u: Option[User]) = {
      u.isDefined
    }
    
    private def noJohn(u: Option[User]) = {
      val uu = u.map(_.login)
      uu.isDefined && uu != Some("john")
    }
    
    get("/", operation(apiOperation[Unit]("getPets"))) {
      "OK"
    }
    
    get("/authenticated", operation(apiOperation[Unit]("authenticated").allows(allowsAuthenticated))) {
      "OK"
    }
    
    get("/admin", operation(apiOperation[Unit]("admin").allows(allowsTom))) {
      "OK"
    }
    
    get("/kate-and-tom", operation(apiOperation[Unit]("getKateAndTom").allows(noJohn))) {
      "OK"
    }
  }
  
  class AdminApi(implicit protected val swagger: SwaggerWithAuth) extends AuthenticatedBase with NativeJsonSupport with SwaggerAuthSupport[User] {
    protected val applicationDescription = "The admin api"
    override protected val applicationName = Some("admin")


    implicit protected def jsonFormats: Formats = DefaultFormats

    private[this] def isAllowed(u: Option[User]) = {
      u.map(_.login) == Some("tom")
    }
    
    get("/", operation(apiOperation[Unit]("adminlist").allows(isAllowed))) {
      println("executing / ")
      "OK"
    }
    
    get("/blah", operation(apiOperation[Unit]("blah").allows(isAllowed))) {
      "OK"
    }
    
    post("/blah", operation(apiOperation[Unit]("createBlah").allows(isAllowed))) {
      "OK"
    }
  }
}
class SwaggerAuthSpec extends MutableScalatraSpec {

  import SwaggerAuthSpec._
  implicit val swagger: SwaggerWithAuth = new SpecSwagger

  
  addServlet(new PetsApi, "/pets/*")
  
  addServlet(new AdminApi, "/admin/*")
  
  addServlet(new ResourcesApp, "/*")
  
  
  private def apis(jv: JValue): List[String] = jv \ "apis" \ "path" \\ classOf[JString]
  private def endpoints(jv: JValue): List[String] = jv \ "apis" \ "operations" \ "httpMethod" \\ classOf[JString]
  private def jsonBody = {
    val b = body
//    println("json body")
    val j = parse(b)
//    println(pretty(render(j)))
    j
  }
  
  "SwaggerAuth" should {
    
    "only render accessible endpoints in resources index" in {
    	get("/api-docs.json") {
    	  status must_== 200
    	  apis(jsonBody).size must_== 1
    	}
    }

    "don't render inaccessible resource for non-admin user" in {
      get("/api-docs.json", "api_token" -> "the_token") {
        status must_== 200
    	  apis(jsonBody).size must_== 1
    	}
    }

    "render all resources for admin user" in {
      get("/api-docs.json", "api_token" -> "token1") {
        status must_== 200
    	  apis(jsonBody).size must_== 2
    	}
    }

    "only render publicly accessible endpoints for pets api" in {
    	get("/pets.json") {
    	  status must_== 200
    	  endpoints(jsonBody).size must_== 1
    	}
    }

    "only render accessible endpoints in pets api for 'john'" in {
      get("/pets.json", "api_token" -> "the_token") {
        status must_== 200
    	  endpoints(jsonBody).size must_== 2
    	}
    }

    "only render accessible endpoints in pets api for 'kate'" in {
    	get("/pets.json", "api_token" -> "token2") {
    	  status must_== 200
				endpoints(jsonBody).size must_== 3
    	}
    }

    "only render accessible endpoints in pets api for 'tom'" in {
    	get("/pets.json", "api_token" -> "token1") {
    	  status must_== 200
				endpoints(jsonBody).size must_== 4
    	}
    }

    "render admin endpoints for admin user" in {
    	get("/admin.json", "api_token" -> "token1") {
    	  status must_== 200
    	  endpoints(jsonBody).size must_== 3
    	}
    }

    "return 404 for non-admin user when requesting admin.json" in {
      get("/admin.json", "api_token" -> "token2") {
    	  status must_== 404 
    	}
    }
    
  }
  
}
