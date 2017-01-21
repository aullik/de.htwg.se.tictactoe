package controllers

import play.api.mvc.Results._
import play.api.mvc._


/**
  */
object TicTacToeApplication {


  def index(request: Request[AnyContent]): Result = {
    Ok(views.html.bootstrap.index())
  }


  def signUpPage(request: Request[AnyContent]): Result = {
    Ok(views.html.bootstrap.signup())
  }

}